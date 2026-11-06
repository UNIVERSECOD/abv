package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.DeviceSyncDTO;
import com.hic.dto.HikUserInfoSearchDTO;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.UserRepository;
import com.hic.service.DeviceService;
import com.hic.service.DeviceSyncService;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.HikDeviceUserImportService;
import com.hic.util.EncryptionUtil;
import com.hic.util.JwtUtil;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = DeviceController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class DeviceControllerTest {

    @org.junit.jupiter.api.BeforeEach
    void tenantContext() { com.hic.util.TenantContext.setTenantId(1L); }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() { com.hic.util.TenantContext.clear(); }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeviceService deviceService;

    @MockBean
    private DeviceSyncService deviceSyncService;

    @MockBean
    private DeviceConfigRepository deviceConfigRepository;

    @MockBean
    private HikDeviceUserImportService hikDeviceUserImportService;

    @MockBean
    private DoorAttendanceSyncService doorAttendanceSyncService;

    @MockBean
    private EncryptionUtil encryptionUtil;

    @MockBean
    private com.hic.repository.BranchRepository branchRepository;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserRepository userRepository;

    private DeviceConfig backendDevice() {
        DeviceConfig device = new DeviceConfig();
        device.setId(1L);
        device.setTenantId(1L);
        device.setDeviceId("10");
        device.setDeviceName("Front Door");
        device.setStatus("ACTIVE");
        return device;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"start", "stop", "sync"})
    void foreignDevicePostIsRejectedBeforeBridge(String operation) throws Exception {
        DeviceConfig foreign = backendDevice();
        foreign.setTenantId(2L);
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(foreign));
        mockMvc.perform(post("/api/devices/1/" + operation)).andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(deviceSyncService, doorAttendanceSyncService);
        verify(deviceConfigRepository, never()).save(any());
    }

    @Test
    void foreignDeviceUpdateAndDeleteCannotMutateEitherSystem() throws Exception {
        DeviceConfig foreign = backendDevice();
        foreign.setTenantId(2L);
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(foreign));
        mockMvc.perform(put("/api/devices/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/devices/1"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(deviceSyncService);
        verify(deviceConfigRepository, never()).save(any());
        verify(deviceConfigRepository, never()).deleteById(any());
    }

    private DeviceSyncDTO.DeviceConfigDTO deviceConfigDto() {
        return new DeviceSyncDTO.DeviceConfigDTO(
                1L, "10", "Front Door", "10.0.0.1", 80, "admin", null, null, null, null,
                "ACTIVE", true, LocalDateTime.of(2026, 9, 23, 10, 30));
    }

    @Test
    void getAll_returns200() throws Exception {
        when(deviceService.getAll()).thenReturn(List.of(deviceConfigDto()));

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].deviceName").value("Front Door"));

        verify(deviceService).syncFromIsapi();
        verify(deviceService).getAll();
    }

    @Test
    void patchEnabled_returns200() throws Exception {
        DeviceConfig backendDevice = backendDevice();
        DeviceSyncDTO.DeviceConfigDTO updated = deviceConfigDto();
        updated.setStatus("INACTIVE");

        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(backendDevice));
        when(deviceSyncService.updateEnabled(eq(10L), any(DeviceSyncDTO.DeviceEnabledDTO.class))).thenReturn(updated);
        when(deviceService.getById(1L)).thenReturn(updated);

        mockMvc.perform(patch("/api/devices/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        verify(deviceConfigRepository).save(backendDevice);
    }

    @Test
    void start_returns200() throws Exception {
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(backendDevice()));
        when(deviceSyncService.startDevice(10L))
                .thenReturn(new DeviceSyncDTO.DeviceRuntimeDTO(10L, true, true, "RUNNING"));

        mockMvc.perform(post("/api/devices/1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(true));
    }

    @Test
    void status_returns200() throws Exception {
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(backendDevice()));
        when(deviceSyncService.getStatus(10L))
                .thenReturn(new DeviceSyncDTO.DeviceStatusDTO(10L, true, 200, "OK"));

        mockMvc.perform(get("/api/devices/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(true));
    }

    @Test
    void sync_returns200() throws Exception {
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(backendDevice()));
        when(deviceSyncService.syncDevice(10L))
                .thenReturn(new DeviceSyncDTO.SyncResultDTO(true, "Synced", 3));

        mockMvc.perform(post("/api/devices/1/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.recordsSynced").value(3));

        verify(doorAttendanceSyncService).syncAllDevices(any(), any(), eq(500));
    }

    @Test
    void importUsers_syncsRecentAttendanceIncludingYesterday() throws Exception {
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(backendDevice()));
        when(hikDeviceUserImportService.importUsersFromDevice(1L))
                .thenReturn(new HikUserInfoSearchDTO.ImportResultDTO(4, 2, 2, 0, "Import tamamlandı."));

        mockMvc.perform(post("/api/devices/1/import-users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(2));

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(doorAttendanceSyncService).syncAllDevicesForImport(start.capture(), end.capture(), eq(500));
        verify(deviceSyncService).syncDeviceNow(eq(10L), any(LocalDateTime.class));
        Assertions.assertThat(start.getValue())
                .isEqualTo(java.time.LocalDate.of(2026, 10, 1).atStartOfDay());
    }

    @Test
    void importUsers_keepsImportedEmployeesWhenAttendanceSyncFails() throws Exception {
        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(backendDevice()));
        when(hikDeviceUserImportService.importUsersFromDevice(1L))
                .thenReturn(new HikUserInfoSearchDTO.ImportResultDTO(4, 2, 2, 0, "Import tamamlandı."));
        when(doorAttendanceSyncService.syncAllDevicesForImport(any(), any(), eq(500)))
                .thenThrow(new IllegalStateException("ISAPI unavailable"));

        mockMvc.perform(post("/api/devices/1/import-users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString(
                        "Keçid tarixçəsi hesablanmadı")));
    }

    @Test
    void create_storesDevicePasswordEncrypted() throws Exception {
        DeviceSyncDTO.DeviceConfigDTO created = deviceConfigDto();
        when(deviceSyncService.createDevice(any(DeviceSyncDTO.DeviceConfigDTO.class))).thenReturn(created);
        when(deviceConfigRepository.findByDeviceId("10")).thenReturn(Optional.empty());
        when(encryptionUtil.encrypt("device-secret")).thenReturn("ENC:device-secret");
        when(deviceConfigRepository.save(any(DeviceConfig.class))).thenAnswer(invocation -> {
            DeviceConfig saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(deviceService.getById(1L)).thenReturn(created);

        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ip":"10.0.0.1","username":"admin","password":"device-secret",
                                 "name":"Front Door","enabled":true}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<DeviceConfig> deviceCaptor = ArgumentCaptor.forClass(DeviceConfig.class);
        verify(deviceConfigRepository).save(deviceCaptor.capture());
        Assertions.assertThat(deviceCaptor.getValue().getPasswordEncrypted()).isEqualTo("ENC:device-secret");
        Assertions.assertThat(deviceCaptor.getValue().isOnline()).isTrue();
        Assertions.assertThat(deviceCaptor.getValue().getLastSyncTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 23, 10, 30));
    }

    @Test
    void update_withoutPassword_preservesStoredPassword() throws Exception {
        DeviceConfig existing = backendDevice();
        existing.setPasswordEncrypted("ENC:existing-secret");
        DeviceSyncDTO.DeviceConfigDTO updated = deviceConfigDto();
        updated.setDeviceName("Renamed Door");

        when(deviceConfigRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(deviceSyncService.updateDevice(eq(10L), any(DeviceSyncDTO.DeviceConfigDTO.class))).thenReturn(updated);
        when(deviceService.getById(1L)).thenReturn(updated);

        mockMvc.perform(put("/api/devices/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ip":"10.0.0.1","username":"admin","name":"Renamed Door","enabled":true}
                                """))
                .andExpect(status().isOk());

        Assertions.assertThat(existing.getPasswordEncrypted()).isEqualTo("ENC:existing-secret");
        verify(encryptionUtil, never()).encrypt(any());
        verify(deviceConfigRepository).save(existing);
    }
}
