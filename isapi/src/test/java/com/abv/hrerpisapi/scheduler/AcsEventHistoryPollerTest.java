package com.abv.hrerpisapi.scheduler;

import com.abv.hrerpisapi.dao.entity.DeviceCursorEntity;
import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceCursorRepository;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import com.abv.hrerpisapi.service.AcsIngestService;
import com.abv.hrerpisapi.service.DeviceCursorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcsEventHistoryPollerTest {

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DeviceCursorRepository cursorRepository;
    @Mock
    private IsapiClient isapiClient;
    @Mock
    private AcsIngestService acsIngestService;
    @Mock
    private DeviceCursorService deviceCursorService;

    @InjectMocks
    private AcsEventHistoryPoller poller;

    @Test
    void resolveStartTime_clampsFutureCursorBeforeNow() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T07:10:00+04:00");

        OffsetDateTime start = AcsEventHistoryPoller.resolveStartTime(now.plusMinutes(5), now);

        assertThat(start).isEqualTo(now.minusMinutes(2));
    }

    @Test
    void pollDevice_recordsSuccessfulPollWhenNoEventsExist() throws Exception {
        DeviceEntity device = new DeviceEntity();
        device.setId(1L);

        DeviceCursorEntity cursor = new DeviceCursorEntity();
        cursor.setDeviceId(1L);
        cursor.setLastSerialNo(42L);
        cursor.setLastEventTime(OffsetDateTime.now().minusMinutes(5));

        when(cursorRepository.findById(1L)).thenReturn(Optional.of(cursor));
        when(isapiClient.checkDeviceStatus(device))
                .thenReturn(new IsapiClient.DeviceStatusCheckResult(true, 200, "OK"));
        when(isapiClient.searchAcsEvents(eq(device), any(OffsetDateTime.class), eq(42L), eq(30)))
                .thenReturn(List.of());

        int ingested = poller.pollDevice(device);

        ArgumentCaptor<DeviceCursorEntity> cursorCaptor = ArgumentCaptor.forClass(DeviceCursorEntity.class);
        verify(cursorRepository).save(cursorCaptor.capture());
        assertThat(ingested).isZero();
        assertThat(cursorCaptor.getValue().getLastPollTime()).isNotNull();
        assertThat(cursorCaptor.getValue().getLastSerialNo()).isEqualTo(42L);
        assertThat(cursorCaptor.getValue().isOnline()).isTrue();
    }

    @Test
    void scheduledPoll_historyFailure_keepsSuccessfulConnectivityStatus() throws Exception {
        DeviceEntity device = new DeviceEntity();
        device.setId(2L);
        device.setIp("192.168.1.20");

        when(deviceRepository.findByEnabledTrue()).thenReturn(List.of(device));
        when(cursorRepository.findById(2L)).thenReturn(Optional.empty());
        when(isapiClient.checkDeviceStatus(device))
                .thenReturn(new IsapiClient.DeviceStatusCheckResult(true, 200, "OK"));
        when(isapiClient.searchAcsEvents(eq(device), any(OffsetDateTime.class), eq(0L), eq(30)))
                .thenThrow(new IOException("Connection refused"));

        poller.poll();

        verify(deviceCursorService).markOnline(2L);
    }
}
