package com.hic.controller;

import com.hic.dto.DeviceEmployeeImportDTO;
import com.hic.repository.UserRepository;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.HikDeviceUserImportService;
import com.hic.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = SetupImportController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
)
class SetupImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HikDeviceUserImportService hikDeviceUserImportService;

    @MockBean
    private DoorAttendanceSyncService doorAttendanceSyncService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserRepository userRepository;

    @Test
    void importEmployees_returnsSummary() throws Exception {
        DeviceEmployeeImportDTO.ImportResult result = new DeviceEmployeeImportDTO.ImportResult();
        result.setBranchId(10L);
        result.setCreated(3);
        result.setSkippedExisting(1);
        result.setSkippedConflict(0);
        result.setMessage("ok");

        when(hikDeviceUserImportService.importUsersFromBranch(any())).thenReturn(result);

        mockMvc.perform(post("/api/setup/import-employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.created").value(3))
                .andExpect(jsonPath("$.data.branchId").value(10));

        verify(hikDeviceUserImportService).importUsersFromBranch(any());
        org.mockito.ArgumentCaptor<LocalDateTime> start = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<LocalDateTime> end = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(doorAttendanceSyncService).syncAllDevices(start.capture(), end.capture(), eq(500));
        org.assertj.core.api.Assertions.assertThat(start.getValue()).isEqualTo(end.getValue().minusDays(3));
    }

    @Test
    void importEmployees_keepsResultWhenAttendanceHistorySyncFails() throws Exception {
        DeviceEmployeeImportDTO.ImportResult result = new DeviceEmployeeImportDTO.ImportResult();
        result.setBranchId(10L);
        result.setCreated(3);
        result.setMessage("Import tamamlandı.");
        when(hikDeviceUserImportService.importUsersFromBranch(any())).thenReturn(result);
        when(doorAttendanceSyncService.syncAllDevices(any(), any(), eq(500)))
                .thenThrow(new IllegalStateException("ISAPI unavailable"));

        mockMvc.perform(post("/api/setup/import-employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(3))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString(
                        "keçid tarixçəsi sinxronlaşdırılmadı")));
    }
}
