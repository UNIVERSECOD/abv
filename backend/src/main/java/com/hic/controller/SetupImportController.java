package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.DeviceEmployeeImportDTO;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.HikDeviceUserImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * Setup-team endpoints for onboarding an existing Hikvision site into this ERP.
 * Restricted to central-office setup accounts.
 */
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
@PreAuthorize("hasRole('HEAD_OFFICE_HR')")
public class SetupImportController {

    private final HikDeviceUserImportService hikDeviceUserImportService;
    private final DoorAttendanceSyncService doorAttendanceSyncService;

    /**
     * Import employees from all (or selected) devices of a branch.
     *
     * <p>POST /api/setup/import-employees
     * Body: {@code { "branchId": 1, "deviceConfigIds": [10, 11] }} —
     * {@code deviceConfigIds} optional; when omitted, all branch devices are scanned.
     */
    @PostMapping("/import-employees")
    public ResponseEntity<ApiResponse<DeviceEmployeeImportDTO.ImportResult>> importEmployees(
            @RequestBody DeviceEmployeeImportDTO.ImportRequest request) {
        DeviceEmployeeImportDTO.ImportResult result = hikDeviceUserImportService.importUsersFromBranch(request);
        try {
            LocalDateTime end = com.hic.util.AppTimeZone.now();
            LocalDateTime start = com.hic.util.AppTimeZone.today().minusDays(1).atStartOfDay();
            doorAttendanceSyncService.syncAllDevicesForImport(start, end, 500);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(SetupImportController.class)
                    .warn("Attendance history sync failed after branch device import {}", request.getBranchId(), ex);
            String importMessage = result.getMessage() == null ? "" : result.getMessage().trim();
            result.setMessage((importMessage.isEmpty() ? "" : importMessage + " ")
                    + "İşçilər import edildi, lakin keçid tarixçəsi sinxronlaşdırılmadı; yenidən sinxron edin.");
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
