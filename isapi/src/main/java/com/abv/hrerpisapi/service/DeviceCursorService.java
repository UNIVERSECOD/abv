package com.abv.hrerpisapi.service;

import com.abv.hrerpisapi.dao.entity.DeviceCursorEntity;
import com.abv.hrerpisapi.dao.repository.DeviceCursorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCursorService {

    private final DeviceCursorRepository deviceCursorRepository;

    @Transactional
    public DeviceCursorEntity resetCursor(Long deviceId) {
        DeviceCursorEntity cursor = deviceCursorRepository.findById(deviceId)
                .orElseGet(() -> {
                    DeviceCursorEntity c = new DeviceCursorEntity();
                    c.setDeviceId(deviceId);
                    return c;
                });

        cursor.setLastSerialNo(0L);
        cursor.setLastEventTime(null);
        DeviceCursorEntity saved = deviceCursorRepository.save(cursor);
        log.info("ActionLog.device.cursor.reset.ended deviceId={} lastSerialNo={} lastEventTime={}",
                saved.getDeviceId(), saved.getLastSerialNo(), saved.getLastEventTime());
        return saved;
    }

    @Transactional
    public DeviceCursorEntity markOffline(Long deviceId) {
        return updateOnlineStatus(deviceId, false);
    }

    @Transactional
    public DeviceCursorEntity markOnline(Long deviceId) {
        return updateOnlineStatus(deviceId, true);
    }

    private DeviceCursorEntity updateOnlineStatus(Long deviceId, boolean online) {
        DeviceCursorEntity cursor = deviceCursorRepository.findById(deviceId)
                .orElseGet(() -> {
                    DeviceCursorEntity created = new DeviceCursorEntity();
                    created.setDeviceId(deviceId);
                    created.setLastSerialNo(0L);
                    return created;
                });

        cursor.setOnline(online);
        if (online) {
            cursor.setLastPollTime(OffsetDateTime.now());
        }
        DeviceCursorEntity saved = deviceCursorRepository.save(cursor);
        log.info("ActionLog.device.cursor.online.updated deviceId={} online={} lastSerialNo={} lastPollTime={}",
                saved.getDeviceId(), saved.isOnline(), saved.getLastSerialNo(), saved.getLastPollTime());
        return saved;
    }
}
