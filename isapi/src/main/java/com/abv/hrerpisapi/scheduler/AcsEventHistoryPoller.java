package com.abv.hrerpisapi.scheduler;

import com.abv.hrerpisapi.dao.entity.DeviceCursorEntity;
import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceCursorRepository;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import com.abv.hrerpisapi.device.model.ParsedAcsEvent;
import com.abv.hrerpisapi.service.AcsIngestService;
import com.abv.hrerpisapi.service.DeviceCursorService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs every minute and fills any events that were missed while the
 * alertStream was disconnected, by querying the device history API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcsEventHistoryPoller {

    private static final int MAX_RESULTS = 30;
    /** Small backward overlap to tolerate clock skew / edge races */
    private static final int OVERLAP_MINUTES = 2;

    @Value("${acs.history.unsupported.cooldown-minutes:60}")
    private long unsupportedCooldownMinutes;

    private final DeviceRepository deviceRepository;
    private final DeviceCursorRepository cursorRepository;
    private final IsapiClient isapiClient;
    private final AcsIngestService acsIngestService;
    private final DeviceCursorService deviceCursorService;

    private final Map<Long, OffsetDateTime> historyPollingDisabledUntil = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> devicePollLocks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        log.info("HistoryPoller tick");
        var devices = deviceRepository.findByEnabledTrue();
        log.info("HistoryPoller tick: enabledDevices={}", devices.size());
        devices.forEach(device -> {
            log.info("HistoryPoller: device={} polling started", device.getId());

            boolean disabled = isHistoryPollingDisabled(device.getId());
            log.info("HistoryPoller: device={} disabled={}", device.getId(), disabled);
            if (disabled) return;

            try {
                if (!pollDeviceIfIdle(device)) {
                    log.info("HistoryPoller: device={} skipped because synchronization is already running", device.getId());
                }
            } catch (IsapiClient.AcsEventHistoryNotSupportedException e) {
                deviceCursorService.markOnline(device.getId());
                disableHistoryPolling(device);
            } catch (IOException e) {
                logPollFailure(device, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logPollFailure(device, e);
            } catch (Exception e) {
                logPollFailure(device, e);
            }
        });
    }

    /** Runs one immediate history synchronization and returns successfully ingested event count. */
    public int pollDevice(DeviceEntity device) throws IOException, InterruptedException {
        ReentrantLock lock = lockFor(device.getId());
        lock.lockInterruptibly();
        try {
            return pollDeviceLocked(device);
        } finally {
            lock.unlock();
        }
    }

    /** Explicit idempotent backfill that does not let the current cursor hide older events. */
    public int pollDeviceSince(DeviceEntity device, OffsetDateTime startTime) throws IOException, InterruptedException {
        ReentrantLock lock = lockFor(device.getId());
        lock.lockInterruptibly();
        try {
            return pollDeviceLocked(device, startTime, true);
        } finally {
            lock.unlock();
        }
    }

    /** Starts a single background synchronization; repeated requests for the same device are ignored. */
    @Async
    public void pollDeviceInBackground(DeviceEntity device) {
        try {
            if (!pollDeviceIfIdle(device)) {
                log.info("HistoryPoller: device={} background synchronization already running", device.getId());
            }
        } catch (IsapiClient.AcsEventHistoryNotSupportedException e) {
            deviceCursorService.markOnline(device.getId());
            disableHistoryPolling(device);
        } catch (IOException e) {
            logPollFailure(device, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logPollFailure(device, e);
        } catch (Exception e) {
            logPollFailure(device, e);
        }
    }

    boolean pollDeviceIfIdle(DeviceEntity device) throws IOException, InterruptedException {
        ReentrantLock lock = lockFor(device.getId());
        if (!lock.tryLock()) {
            return false;
        }
        try {
            pollDeviceLocked(device);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private int pollDeviceLocked(DeviceEntity device) throws IOException, InterruptedException {
        return pollDeviceLocked(device, null, false);
    }

    private int pollDeviceLocked(DeviceEntity device, OffsetDateTime forcedStartTime, boolean ignoreCursorSerial)
            throws IOException, InterruptedException {
        IsapiClient.DeviceStatusCheckResult status = isapiClient.checkDeviceStatus(device);
        if (!status.online()) {
            deviceCursorService.markOffline(device.getId());
            throw new IOException("Device status check failed with HTTP " + status.statusCode());
        }
        // Connectivity and history progress are separate: a slow backlog must not keep the device offline.
        deviceCursorService.markOnline(device.getId());

        List<ParsedAcsEvent> events = fetchMissedEvents(device, forcedStartTime, ignoreCursorSerial);
        log.info("HistoryPoller: device={} history returned {} event(s)", device.getId(), events.size());

        long maxSerialIngested = 0L;
        OffsetDateTime maxEventTimeIngested = null;
        int ingestedCount = 0;

        for (ParsedAcsEvent event : events) {
            try {
                acsIngestService.ingest(device, event);
                ingestedCount++;

                if (event.serialNo() > maxSerialIngested) {
                    maxSerialIngested = event.serialNo();
                }
                if (event.eventTime() != null
                        && (maxEventTimeIngested == null || event.eventTime().isAfter(maxEventTimeIngested))) {
                    maxEventTimeIngested = event.eventTime();
                }
            } catch (Exception ex) {
                // Do not advance the event cursor beyond an event that failed to ingest.
                log.warn("HistoryPoller: ingest failed device={} serialNo={} eventTime={} msg={}",
                        device.getId(), event.serialNo(), event.eventTime(), ex.getMessage());
                log.debug("HistoryPoller: ingest exception", ex);
            }
        }

        updateCursorAfterPoll(device.getId(), maxSerialIngested, maxEventTimeIngested, OffsetDateTime.now());
        return ingestedCount;
    }

    private ReentrantLock lockFor(Long deviceId) {
        return devicePollLocks.computeIfAbsent(deviceId, ignored -> new ReentrantLock());
    }

    private List<ParsedAcsEvent> fetchMissedEvents(
            DeviceEntity device,
            OffsetDateTime forcedStartTime,
            boolean ignoreCursorSerial)
            throws IOException, InterruptedException {

        DeviceCursorEntity cursor = cursorRepository.findById(device.getId()).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime startTime = forcedStartTime != null
                ? forcedStartTime
                : resolveStartTime(cursor != null ? cursor.getLastEventTime() : null, now);

        long afterSerialNo = !ignoreCursorSerial && cursor != null && cursor.getLastSerialNo() != null
                ? cursor.getLastSerialNo()
                : 0L;

        return isapiClient.searchAcsEvents(device, startTime, afterSerialNo, MAX_RESULTS);
    }

    static OffsetDateTime resolveStartTime(OffsetDateTime lastEventTime, OffsetDateTime now) {
        OffsetDateTime candidate = lastEventTime != null
                ? lastEventTime.minusMinutes(OVERLAP_MINUTES)
                : now.minusHours(1);

        if (!candidate.isBefore(now)) {
            return now.minusMinutes(OVERLAP_MINUTES);
        }
        return candidate;
    }

    private void updateCursorAfterPoll(Long deviceId,
                                       long maxSerialIngested,
                                       OffsetDateTime maxEventTimeIngested,
                                       OffsetDateTime pollTime) {
        DeviceCursorEntity cursor = cursorRepository.findById(deviceId)
                .orElseGet(() -> {
                    DeviceCursorEntity c = new DeviceCursorEntity();
                    c.setDeviceId(deviceId);
                    c.setLastSerialNo(0L);
                    return c;
                });

        Long currentSerial = cursor.getLastSerialNo();
        if (maxSerialIngested > 0 && (currentSerial == null || maxSerialIngested > currentSerial)) {
            cursor.setLastSerialNo(maxSerialIngested);
        }

        OffsetDateTime currentEventTime = cursor.getLastEventTime();
        if (maxEventTimeIngested != null && (currentEventTime == null || maxEventTimeIngested.isAfter(currentEventTime))) {
            cursor.setLastEventTime(maxEventTimeIngested);
        }

        cursor.setLastPollTime(pollTime);
        cursor.setOnline(true);
        cursorRepository.save(cursor);
        log.info("HistoryPoller: cursor updated deviceId={} online={} lastSerialNo={} lastEventTime={} lastPollTime={}",
                deviceId, cursor.isOnline(), cursor.getLastSerialNo(), cursor.getLastEventTime(), cursor.getLastPollTime());
    }

    private void logPollFailure(DeviceEntity device, Exception exception) {
        log.warn("HistoryPoller failed for device {} ({}): {}",
                device.getId(), device.getIp(), exception.getMessage());
        log.debug("HistoryPoller exception for device {}", device.getId(), exception);
    }

    private boolean isHistoryPollingDisabled(Long deviceId) {
        OffsetDateTime disabledUntil = historyPollingDisabledUntil.get(deviceId);
        if (disabledUntil == null) {
            return false;
        }
        if (disabledUntil.isAfter(OffsetDateTime.now())) {
            return true;
        }
        historyPollingDisabledUntil.remove(deviceId, disabledUntil);
        return false;
    }

    private void disableHistoryPolling(DeviceEntity device) {
        OffsetDateTime disabledUntil = OffsetDateTime.now()
                .plusMinutes(Math.max(1L, unsupportedCooldownMinutes));

        historyPollingDisabledUntil.put(device.getId(), disabledUntil);

        log.info("HistoryPoller: disabling history polling for device {} ({}) until {} " +
                        "because /ISAPI/AccessControl/AcsEvent is not supported",
                device.getId(), device.getIp(), disabledUntil);
    }

    @PostConstruct
    void init() {
        log.info("HistoryPoller bean initialized");
    }
}
