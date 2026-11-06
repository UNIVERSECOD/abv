package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.DeviceEmployeeImportDTO;
import com.hic.dto.DeviceSyncDTO;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.service.DeviceSyncService;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.HikDeviceUserImportService;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private final DeviceConfigRepository deviceConfigRepository;
    private final DeviceSyncService deviceSyncService;

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
        LocalDateTime start = com.hic.util.AppTimeZone.today().minusDays(1).atStartOfDay();
        List<String> historyWarnings = pullSelectedDeviceHistories(request, start);
        try {
            LocalDateTime end = com.hic.util.AppTimeZone.now();
            doorAttendanceSyncService.syncAllDevicesForImport(start, end, 500);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(SetupImportController.class)
                    .warn("Attendance history sync failed after branch device import {}", request.getBranchId(), ex);
            historyWarnings.add("Keçid tarixçəsi hesablanmadı; yenidən sinxron edin.");
        }
        if (!historyWarnings.isEmpty()) {
            String importMessage = result.getMessage() == null ? "" : result.getMessage().trim();
            result.setMessage((importMessage.isEmpty() ? "" : importMessage + " ")
                    + String.join(" ", historyWarnings));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private List<String> pullSelectedDeviceHistories(
            DeviceEmployeeImportDTO.ImportRequest request,
            LocalDateTime start) {
        List<String> warnings = new java.util.ArrayList<>();
        Long tenantId = TenantContext.getTenantId();
        Set<Long> requestedIds = request.getDeviceConfigIds() == null
                ? Set.of()
                : new LinkedHashSet<>(request.getDeviceConfigIds());
        List<DeviceConfig> devices = deviceConfigRepository.findByBranchId(request.getBranchId()).stream()
                .filter(device -> tenantId == null || tenantId.equals(device.getTenantId()))
                .filter(device -> requestedIds.isEmpty() || requestedIds.contains(device.getId()))
                .toList();
        for (DeviceConfig device : devices) {
            String label = device.getDeviceName() != null ? device.getDeviceName() : "Cihaz #" + device.getId();
            try {
                Long bridgeId = Long.valueOf(device.getDeviceId().trim());
                DeviceSyncDTO.SyncResultDTO sync = deviceSyncService.syncDeviceNow(bridgeId, start);
                if (sync == null || !sync.isSuccess()) {
                    warnings.add(label + " tarixçəsi tam çəkilmədi.");
                }
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(SetupImportController.class)
                        .warn("Device history pull failed for deviceConfigId={}", device.getId(), ex);
                warnings.add(label + " tarixçəsi çəkilmədi.");
            }
        }
        return warnings;
    }
}
