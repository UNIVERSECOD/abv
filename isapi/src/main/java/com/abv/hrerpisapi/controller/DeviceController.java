package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.entity.DeviceCursorEntity;
import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceCursorRepository;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import com.abv.hrerpisapi.scheduler.AcsEventHistoryPoller;
import com.abv.hrerpisapi.service.DeviceCursorService;
import com.abv.hrerpisapi.service.DeviceWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceRepository deviceRepository;
    private final DeviceCursorRepository deviceCursorRepository;
    private final DeviceCursorService deviceCursorService;
    private final DeviceWorkerService deviceWorkerService;
    private final IsapiClient isapiClient;
    private final AcsEventHistoryPoller historyPoller;

    @GetMapping
    public List<DeviceResponse> list(@RequestParam(required = false) Boolean enabled) {
        log.info("ActionLog.device.list.started enabled={}", enabled);
        List<DeviceEntity> devices = enabled == null
                ? deviceRepository.findAll()
                : deviceRepository.findByEnabled(enabled);
        Map<Long, DeviceCursorEntity> cursorsByDeviceId = deviceCursorRepository
                .findAllById(devices.stream().map(DeviceEntity::getId).toList())
                .stream()
                .collect(Collectors.toMap(DeviceCursorEntity::getDeviceId, cursor -> cursor));
        log.info("ActionLog.device.list.finished enabled={}", enabled);
        return devices.stream()
                .map(device -> toResponse(device, cursorsByDeviceId.get(device.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable Long id) {
        DeviceEntity device = requireDevice(id);
        return toResponse(device, findCursor(device.getId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse create(@RequestBody DeviceUpsertRequest request) {
        log.info("ActionLog.device.create.started ip={} enabled={}", trimToNull(request.ip()), request.enabled());
        DeviceEntity device = new DeviceEntity();
        applyUpsert(device, request, true);
        DeviceEntity saved = deviceRepository.save(device);
        if (saved.isEnabled()) {
            deviceWorkerService.startDevice(saved);
        }
        log.info("ActionLog.device.create.ended deviceId={} ip={} enabled={}", saved.getId(), saved.getIp(), saved.isEnabled());
        return toResponse(saved, findCursor(saved.getId()));
    }

    @PutMapping("/{id}")
    public DeviceResponse update(@PathVariable Long id, @RequestBody DeviceUpsertRequest request) {
        log.info("ActionLog.device.update.started deviceId={} ip={} enabled={}", id, trimToNull(request.ip()), request.enabled());
        DeviceEntity device = requireDevice(id);
        String previousIp = device.getIp();
        String previousUsername = device.getUsername();
        String previousPassword = device.getPassword();
        boolean wasEnabled = device.isEnabled();
        applyUpsert(device, request, false);
        boolean connectionChanged = !Objects.equals(previousIp, device.getIp())
                || !Objects.equals(previousUsername, device.getUsername())
                || !Objects.equals(previousPassword, device.getPassword());
        DeviceEntity saved = deviceRepository.save(device);
        if (connectionChanged) {
            deviceCursorService.markOffline(saved.getId());
        }
        if (saved.isEnabled()) {
            if (wasEnabled && connectionChanged) {
                deviceWorkerService.restartDevice(saved);
            } else {
                deviceWorkerService.startDevice(saved);
            }
        } else {
            deviceWorkerService.stopDevice(saved.getId());
        }
        log.info("ActionLog.device.update.ended deviceId={} ip={} enabled={}", saved.getId(), saved.getIp(), saved.isEnabled());
        return toResponse(saved, findCursor(saved.getId()));
    }

    @PatchMapping("/{id}/enabled")
    public DeviceResponse updateEnabled(@PathVariable Long id, @RequestBody DeviceEnabledRequest request) {
        log.info("ActionLog.device.enabled.update.started deviceId={} enabled={}", id, request.enabled());
        DeviceEntity device = requireDevice(id);
        device.setEnabled(request.enabled());
        DeviceEntity saved = deviceRepository.save(device);
        if (saved.isEnabled()) {
            deviceWorkerService.startDevice(saved);
        } else {
            deviceCursorService.markOffline(saved.getId());
            deviceWorkerService.stopDevice(saved.getId());
        }
        log.info("ActionLog.device.enabled.update.ended deviceId={} enabled={}", saved.getId(), saved.isEnabled());
        return toResponse(saved, findCursor(saved.getId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        log.info("ActionLog.device.delete.started deviceId={}", id);
        DeviceEntity device = requireDevice(id);
        deviceWorkerService.stopDevice(device.getId());
        deviceRepository.delete(device);
        log.info("ActionLog.device.delete.ended deviceId={}", id);
    }

    @PostMapping("/{id}/start")
    public DeviceRuntimeResponse start(@PathVariable Long id) {
        log.info("ActionLog.device.alertStream.start.started deviceId={}", id);
        DeviceEntity device = requireDevice(id);
        deviceWorkerService.startDevice(device);
        DeviceRuntimeResponse response = new DeviceRuntimeResponse(
                device.getId(),
                device.isEnabled(),
                deviceWorkerService.isRunning(device.getId()));
        log.info("ActionLog.device.alertStream.start.ended deviceId={} running={}", response.id(), response.running());
        return response;
    }

    @PostMapping("/{id}/stop")
    public DeviceRuntimeResponse stop(@PathVariable Long id) {
        log.info("ActionLog.device.alertStream.stop.started deviceId={}", id);
        DeviceEntity device = requireDevice(id);
        deviceWorkerService.stopDevice(device.getId());
        DeviceRuntimeResponse response = new DeviceRuntimeResponse(
                device.getId(),
                device.isEnabled(),
                deviceWorkerService.isRunning(device.getId()));
        log.info("ActionLog.device.alertStream.stop.ended deviceId={} running={}", response.id(), response.running());
        return response;
    }

    @PostMapping("/{id}/sync")
    public DeviceSyncResponse sync(@PathVariable Long id) {
        DeviceEntity device = requireDevice(id);
        deviceWorkerService.startDevice(device);
        IsapiClient.DeviceStatusCheckResult status = isapiClient.checkDeviceStatus(device);
        if (!status.online()) {
            deviceCursorService.markOffline(device.getId());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Device connection failed with HTTP " + status.statusCode());
        }
        deviceCursorService.markOnline(device.getId());
        historyPoller.pollDeviceInBackground(device);
        return new DeviceSyncResponse(true, "Device is online; history synchronization started", 0);
    }

    @PostMapping("/{id}/sync-now")
    public DeviceSyncResponse syncNow(
            @PathVariable Long id,
            @RequestParam(required = false) OffsetDateTime start) {
        DeviceEntity device = requireDevice(id);
        deviceWorkerService.startDevice(device);
        try {
            int recordsSynced = start != null
                    ? historyPoller.pollDeviceSince(device, start)
                    : historyPoller.pollDevice(device);
            return new DeviceSyncResponse(true, "Device history synchronization completed", recordsSynced);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Device history synchronization was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Device history synchronization failed", ex);
        }
    }

    @GetMapping("/{id}/status")
    public DeviceStatusResponse status(@PathVariable Long id) {
        DeviceEntity device = requireDevice(id);
        log.info("ActionLog.device.status.check.started deviceId={} ip={}", device.getId(), device.getIp());
        IsapiClient.DeviceStatusCheckResult status = isapiClient.checkDeviceStatus(device);
        if (status.online()) {
            deviceCursorService.markOnline(device.getId());
        } else {
            deviceCursorService.markOffline(device.getId());
        }
        DeviceStatusResponse response = new DeviceStatusResponse(
                device.getId(),
                status.online(),
                status.statusCode(),
                status.responseSnippet());
        log.info("ActionLog.device.status.check.ended deviceId={} ip={} online={} statusCode={}",
                device.getId(), device.getIp(), response.online(), response.statusCode());
        return response;
    }

    @PostMapping("/{id}/cursor/reset")
    public CursorResetResponse resetCursor(@PathVariable Long id) {
        DeviceEntity device = requireDevice(id);
        DeviceCursorEntity saved = deviceCursorService.resetCursor(device.getId());
        return new CursorResetResponse(saved.getDeviceId(), saved.getLastSerialNo(), saved.getLastEventTime());
    }

    private DeviceEntity requireDevice(Long id) {
        return deviceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    private void applyUpsert(DeviceEntity device, DeviceUpsertRequest request, boolean create) {
        device.setIp(requireNotBlank(request.ip(), "ip is required"));
        device.setUsername(requireNotBlank(request.username(), "username is required"));
        device.setName(trimToNull(request.name()));
        if (request.enabled() != null) {
            device.setEnabled(request.enabled());
        }

        String password = trimToNull(request.password());
        if (create && password == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password is required");
        }
        if (password != null) {
            device.setPassword(password);
        }
    }

    private String requireNotBlank(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private DeviceCursorEntity findCursor(Long deviceId) {
        return deviceCursorRepository.findById(deviceId).orElse(null);
    }

    private OffsetDateTime cursorSyncTime(DeviceCursorEntity cursor) {
        if (cursor == null) {
            return null;
        }
        return cursor.getLastPollTime() != null ? cursor.getLastPollTime() : cursor.getLastEventTime();
    }

    private DeviceResponse toResponse(DeviceEntity device, DeviceCursorEntity cursor) {
        return new DeviceResponse(
                device.getId(),
                device.getIp(),
                device.getUsername(),
                device.getName(),
                device.isEnabled(),
                deviceWorkerService.isRunning(device.getId()),
                device.isEnabled() && cursor != null && cursor.isOnline(),
                cursorSyncTime(cursor));
    }

    public record DeviceResponse(
            Long id,
            String ip,
            String username,
            String name,
            boolean enabled,
            boolean running,
            boolean online,
            OffsetDateTime lastSyncTime
    ) {
    }

    public record DeviceUpsertRequest(
            String ip,
            String username,
            String password,
            String name,
            Boolean enabled
    ) {
    }

    public record DeviceEnabledRequest(boolean enabled) {
    }

    public record DeviceRuntimeResponse(
            Long id,
            boolean enabled,
            boolean running
    ) {
    }

    public record DeviceStatusResponse(
            Long id,
            boolean online,
            int statusCode,
            String responseSnippet
    ) {
    }

    public record DeviceSyncResponse(
            boolean success,
            String message,
            int recordsSynced
    ) {
    }

    public record CursorResetResponse(
            Long deviceId,
            Long lastSerialNo,
            OffsetDateTime lastEventTime
    ) {
    }
}
