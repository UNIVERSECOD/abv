package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.entity.DeviceCursorEntity;
import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceCursorRepository;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import com.abv.hrerpisapi.scheduler.AcsEventHistoryPoller;
import com.abv.hrerpisapi.service.DeviceCursorService;
import com.abv.hrerpisapi.service.DeviceWorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceControllerTest {
    @Mock
    private com.abv.hrerpisapi.device.DeviceTargetPolicy targetPolicy;

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DeviceCursorRepository deviceCursorRepository;
    @Mock
    private DeviceCursorService deviceCursorService;
    @Mock
    private DeviceWorkerService deviceWorkerService;
    @Mock
    private IsapiClient isapiClient;
    @Mock
    private AcsEventHistoryPoller historyPoller;

    @InjectMocks
    private DeviceController controller;

    @Test
    void resetCursor_resetsLastSerialNoAndLastEventTime() {
        DeviceEntity device = new DeviceEntity();
        device.setId(1L);

        DeviceCursorEntity existingCursor = new DeviceCursorEntity();
        existingCursor.setDeviceId(1L);
        existingCursor.setLastSerialNo(0L);
        existingCursor.setLastEventTime(null);

        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(deviceCursorService.resetCursor(1L)).thenReturn(existingCursor);

        DeviceController.CursorResetResponse response = controller.resetCursor(1L);

        verify(deviceCursorService).resetCursor(1L);
        assertThat(response.deviceId()).isEqualTo(1L);
        assertThat(response.lastSerialNo()).isEqualTo(0L);
        assertThat(response.lastEventTime()).isNull();
    }

    @Test
    void list_prefersLastSuccessfulPollTime() {
        DeviceEntity device = new DeviceEntity();
        device.setId(1L);
        device.setIp("192.168.1.10");
        device.setUsername("admin");
        device.setName("Front Door");
        device.setEnabled(true);

        DeviceCursorEntity cursor = new DeviceCursorEntity();
        cursor.setDeviceId(1L);
        cursor.setLastEventTime(OffsetDateTime.parse("2026-05-18T10:15:30Z"));
        OffsetDateTime lastPollTime = OffsetDateTime.parse("2026-05-18T10:20:30Z");
        cursor.setLastPollTime(lastPollTime);
        cursor.setOnline(true);

        when(deviceRepository.findAll()).thenReturn(List.of(device));
        when(deviceCursorRepository.findAllById(List.of(1L))).thenReturn(List.of(cursor));
        when(deviceWorkerService.isRunning(1L)).thenReturn(true);

        List<DeviceController.DeviceResponse> response = controller.list(null);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).online()).isTrue();
        assertThat(response.get(0).lastSyncTime()).isEqualTo(lastPollTime);
    }

    @Test
    void list_usesLastEventTimeWhenPollTimeIsMissing() {
        DeviceEntity device = enabledDevice();
        OffsetDateTime lastEventTime = OffsetDateTime.parse("2026-09-22T18:19:43Z");
        DeviceCursorEntity cursor = new DeviceCursorEntity();
        cursor.setDeviceId(1L);
        cursor.setLastEventTime(lastEventTime);

        when(deviceRepository.findAll()).thenReturn(List.of(device));
        when(deviceCursorRepository.findAllById(List.of(1L))).thenReturn(List.of(cursor));

        List<DeviceController.DeviceResponse> response = controller.list(null);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).online()).isFalse();
        assertThat(response.get(0).lastSyncTime()).isEqualTo(lastEventTime);
    }

    @Test
    void sync_marksReachableDeviceOnlineAndStartsHistoryInBackground() {
        DeviceEntity device = new DeviceEntity();
        device.setId(1L);

        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(isapiClient.checkDeviceStatus(device))
                .thenReturn(new IsapiClient.DeviceStatusCheckResult(true, 200, "OK"));

        DeviceController.DeviceSyncResponse response = controller.sync(1L);

        verify(deviceWorkerService).startDevice(device);
        verify(deviceCursorService).markOnline(1L);
        verify(historyPoller).pollDeviceInBackground(device);
        assertThat(response.success()).isTrue();
        assertThat(response.recordsSynced()).isZero();
    }

    @Test
    void sync_connectionFailure_marksDeviceOffline() {
        DeviceEntity device = enabledDevice();
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(isapiClient.checkDeviceStatus(device))
                .thenReturn(new IsapiClient.DeviceStatusCheckResult(false, 401, "Unauthorized"));

        assertThatThrownBy(() -> controller.sync(1L))
                .isInstanceOf(ResponseStatusException.class);

        verify(deviceCursorService).markOffline(1L);
        verify(historyPoller, never()).pollDeviceInBackground(device);
    }

    @Test
    void syncNow_waitsForHistoryAndReturnsIngestedCount() throws Exception {
        DeviceEntity device = enabledDevice();
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(historyPoller.pollDevice(device)).thenReturn(17);

        DeviceController.DeviceSyncResponse response = controller.syncNow(1L, null);

        verify(deviceWorkerService).startDevice(device);
        verify(historyPoller).pollDevice(device);
        assertThat(response.success()).isTrue();
        assertThat(response.recordsSynced()).isEqualTo(17);
    }

    @Test
    void updateName_withBlankPassword_preservesCredentialAndWorker() {
        DeviceEntity device = enabledDevice();
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(deviceRepository.save(device)).thenReturn(device);

        DeviceController.DeviceResponse response = controller.update(1L,
                new DeviceController.DeviceUpsertRequest(
                        "192.168.1.10", "admin", "", "Renamed Door", true));

        assertThat(device.getPassword()).isEqualTo("device-secret");
        assertThat(response.name()).isEqualTo("Renamed Door");
        verify(deviceWorkerService).startDevice(device);
        verify(deviceWorkerService, never()).restartDevice(device);
        verify(deviceCursorService, never()).markOffline(1L);
    }

    @Test
    void updatePassword_restartsWorkerWithNewCredential() {
        when(isapiClient.checkDeviceStatus(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IsapiClient.DeviceStatusCheckResult(true, 200, "device"));
        DeviceEntity device = enabledDevice();
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(deviceRepository.save(device)).thenReturn(device);

        controller.update(1L, new DeviceController.DeviceUpsertRequest(
                "192.168.1.10", "admin", "new-secret", "Front Door", true));

        assertThat(device.getPassword()).isEqualTo("new-secret");
        verify(deviceCursorService).markOnline(1L);
        verify(deviceWorkerService).restartDevice(device);
        verify(deviceWorkerService, never()).startDevice(device);
    }

    @Test
    void updateIp_marksVerifiedDeviceOnlineAndRestartsWorker() {
        when(isapiClient.checkDeviceStatus(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IsapiClient.DeviceStatusCheckResult(true, 200, "device"));
        DeviceEntity device = enabledDevice();
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        when(deviceRepository.save(device)).thenReturn(device);

        controller.update(1L, new DeviceController.DeviceUpsertRequest(
                "192.168.1.99", "admin", "", "Front Door", true));

        verify(deviceCursorService).markOnline(1L);
        verify(deviceWorkerService).restartDevice(device);
        verify(deviceWorkerService, never()).startDevice(device);
    }

    private DeviceEntity enabledDevice() {
        DeviceEntity device = new DeviceEntity();
        device.setId(1L);
        device.setIp("192.168.1.10");
        device.setUsername("admin");
        device.setPassword("device-secret");
        device.setName("Front Door");
        device.setEnabled(true);
        return device;
    }

    @Test
    void createRejectsWrongPasswordBeforeSavingOrStartingWorker() {
        when(isapiClient.checkDeviceStatus(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IsapiClient.DeviceStatusCheckResult(false, 401, "unauthorized"));
        assertThatThrownBy(() -> controller.create(new DeviceController.DeviceUpsertRequest(
                "10.0.0.5", "admin", "test-only", "Terminal", true)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("giriş məlumatlarını");
        verify(deviceRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(deviceWorkerService, deviceCursorService);
    }

    @Test
    void createRejectsForbiddenEndpointBeforeNetworkCall() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST))
                .when(targetPolicy).requireAllowed("169.254.169.254");
        assertThatThrownBy(() -> controller.create(new DeviceController.DeviceUpsertRequest(
                "169.254.169.254", "admin", "test-only", "Terminal", true)))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(isapiClient, deviceRepository, deviceWorkerService);
    }

    @Test
    void createVerifiedDeviceIsImmediatelyOnline() {
        when(isapiClient.checkDeviceStatus(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IsapiClient.DeviceStatusCheckResult(true, 200, "device-info"));
        when(deviceRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            DeviceEntity device = invocation.getArgument(0); device.setId(9L); return device;
        });
        DeviceCursorEntity cursor = new DeviceCursorEntity(); cursor.setDeviceId(9L); cursor.setOnline(true);
        when(deviceCursorService.markOnline(9L)).thenReturn(cursor);
        var response = controller.create(new DeviceController.DeviceUpsertRequest(
                "10.0.0.5", "admin", "test-only", "Terminal", true));
        assertThat(response.online()).isTrue();
        verify(deviceWorkerService).startDevice(org.mockito.ArgumentMatchers.any());
    }
}
