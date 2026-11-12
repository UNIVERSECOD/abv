package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.DeviceSyncDTO;
import com.hic.dto.DoorDTO;
import com.hic.dto.HikUserInfoSearchDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.service.DeviceService;
import com.hic.service.AnprAreaConfiguration;
import com.hic.service.DeviceSyncService;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.HikDeviceUserImportService;
import com.hic.util.EncryptionUtil;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class DeviceController {
    private final DeviceService deviceService;
    private final DeviceSyncService deviceSyncService;
    private final DeviceConfigRepository deviceConfigRepository;
    private final HikDeviceUserImportService hikDeviceUserImportService;
    private final DoorAttendanceSyncService doorAttendanceSyncService;
    private final EncryptionUtil encryptionUtil;
    private final com.hic.repository.BranchRepository branchRepository;
    private final com.hic.service.DeviceTargetPolicy targetPolicy;
    private final AnprAreaConfiguration anprAreas;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceSyncDTO.DeviceConfigDTO>>> getAll() {
        // Best-effort sync: if ISAPI is unavailable, still return cached DB records
        try {
            deviceService.syncFromIsapi();
        } catch (Exception ex) {
            // Log but do not fail the request; DB data will still be returned
            org.slf4j.LoggerFactory.getLogger(DeviceController.class)
                    .warn("ISAPI sync skipped during getAll: {}", ex.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.success(deviceService.getAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(id)));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.SyncResultDTO>> syncFromIsapi() {
        return ResponseEntity.ok(ApiResponse.success(deviceService.syncFromIsapi()));
    }

    @PostMapping
    @Transactional
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> create(
            @RequestBody DeviceSyncDTO.DeviceUpsertRequest req) {
        DeviceSyncDTO.DeviceConfigDTO dto = toConfigDTO(req);
        if (Boolean.TRUE.equals(req.getAnpr())) lockDeviceTenant();
        String role = Boolean.TRUE.equals(req.getAnpr())
                ? anprAreas.validate(TenantContext.getTenantId(), req.getBranchId(), req.getDoorRole(), req.getIp(), null) : null;
        DeviceSyncDTO.DeviceConfigDTO isapiResult = deviceSyncService.createDevice(dto);
        DeviceConfig backendDevice = upsertBackendDevice(isapiResult, req.getBranchId(), req.getPassword(), role);
        reconcileAnprPair(backendDevice);
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(backendDevice.getId())));
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> update(
            @PathVariable Long id,
            @RequestBody DeviceSyncDTO.DeviceUpsertRequest req) {
        lockDeviceTenant();
        DeviceConfig backendDevice = requireOwnedDevice(id);
        if (req.getAnpr() != null && req.getAnpr() != backendDevice.isAnpr()) {
            throw new BadRequestException("Cihaz növü qeydiyyatdan sonra dəyişdirilə bilməz.");
        }
        if (req.getBranchId() == null) req.setBranchId(backendDevice.getBranchId());
        DeviceSyncDTO.DeviceConfigDTO dto = toConfigDTO(req);
        String role = backendDevice.isAnpr() ? anprAreas.validate(backendDevice.getTenantId(),
                req.getBranchId(), req.getDoorRole(), req.getIp(), backendDevice) : null;
        DeviceSyncDTO.DeviceConfigDTO isapiResult = deviceSyncService.updateDevice(
                toIsapiId(backendDevice.getDeviceId()), dto);
        updateBackendDevice(backendDevice, isapiResult, req.getBranchId(), req.getPassword());
        if (role != null && !role.equals(backendDevice.getDoorRole())) {
            backendDevice.setDoorRole(role);
            backendDevice.setAttendanceRoleSince(com.hic.util.AppTimeZone.now());
        }
        reconcileAnprPair(backendDevice);
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(id)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        lockDeviceTenant();
        DeviceConfig backendDevice = requireOwnedDevice(id);
        try {
            deviceSyncService.deleteDevice(toIsapiId(backendDevice.getDeviceId()));
        } catch (Exception e) {
            // ISAPI delete may fail if device already removed there; still remove backend record
        }
        deviceConfigRepository.deleteById(id);
        reconcileAnprPair(backendDevice);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/{id}/enabled")
    @Transactional
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> updateEnabled(
            @PathVariable Long id,
            @RequestBody DeviceSyncDTO.DeviceEnabledDTO dto) {
        lockDeviceTenant();
        DeviceConfig backendDevice = requireOwnedDevice(id);
        DeviceSyncDTO.DeviceConfigDTO isapiResult = deviceSyncService.updateEnabled(
                toIsapiId(backendDevice.getDeviceId()), dto);
        backendDevice.setStatus(isapiResult.getStatus());
        backendDevice.setOnline(isapiResult.isOnline());
        updateLastSuccessfulSync(backendDevice, isapiResult.getLastSyncTime());
        deviceConfigRepository.save(backendDevice);
        reconcileAnprPair(backendDevice);
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(id)));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceRuntimeDTO>> start(@PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        return ResponseEntity.ok(ApiResponse.success(
                deviceSyncService.startDevice(toIsapiId(backendDevice.getDeviceId()))));
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceRuntimeDTO>> stop(@PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        return ResponseEntity.ok(ApiResponse.success(
                deviceSyncService.stopDevice(toIsapiId(backendDevice.getDeviceId()))));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceStatusDTO>> status(@PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        return ResponseEntity.ok(ApiResponse.success(
                deviceSyncService.getStatus(toIsapiId(backendDevice.getDeviceId()))));
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.SyncResultDTO>> sync(@PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        DeviceSyncDTO.SyncResultDTO result = deviceSyncService.syncDevice(toIsapiId(backendDevice.getDeviceId()));
        if (result != null && result.isSuccess()) {
            LocalDateTime end = com.hic.util.AppTimeZone.now();
            doorAttendanceSyncService.syncAllDevices(end.minusDays(3), end, 500);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{deviceConfigId}/assign-door")
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> assignDoor(
            @PathVariable Long deviceConfigId,
            @RequestBody DoorDTO.AssignDoorRequest dto) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.assignDoor(deviceConfigId, dto)));
    }

    /**
     * Pulls all users registered on the physical Hikvision device and imports
     * them as Employee records into the local database.
     *
     * <p>Existing employees (matched by employeeNo) are skipped so that
     * manually-edited HR data is never overwritten.
     *
     * <p>POST /api/devices/{id}/import-users
     */
    @PostMapping("/{id}/import-users")
    @PreAuthorize("hasRole('HEAD_OFFICE_HR')")
    public ResponseEntity<ApiResponse<HikUserInfoSearchDTO.ImportResultDTO>> importUsers(
            @PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        HikUserInfoSearchDTO.ImportResultDTO result = hikDeviceUserImportService.importUsersFromDevice(id);
        String historyWarning = null;
        LocalDateTime start = java.time.LocalDate.of(2026, 10, 1).atStartOfDay();
        try {
            DeviceSyncDTO.SyncResultDTO history = deviceSyncService.syncDeviceNow(
                    toIsapiId(backendDevice.getDeviceId()), start);
            if (history == null || !history.isSuccess()) {
                historyWarning = "Cihaz tarixçəsi tam çəkilmədi.";
            }
        } catch (Exception ex) {
            historyWarning = "Cihaz tarixçəsi çəkilmədi.";
            org.slf4j.LoggerFactory.getLogger(DeviceController.class)
                    .warn("Device history pull failed after importing users from device {}", id, ex);
        }
        try {
            LocalDateTime end = com.hic.util.AppTimeZone.now();
            doorAttendanceSyncService.syncAllDevicesForImport(start, end, 500);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(DeviceController.class)
                    .warn("Attendance history sync failed after importing users from device {}", id, ex);
            historyWarning = "Keçid tarixçəsi hesablanmadı; yenidən sinxron edin.";
        }
        if (historyWarning != null) {
            String importMessage = result.getMessage() == null ? "" : result.getMessage().trim();
            result.setMessage((importMessage.isEmpty() ? "" : importMessage + " ") + historyWarning);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private DeviceSyncDTO.DeviceConfigDTO toConfigDTO(DeviceSyncDTO.DeviceUpsertRequest req) {
        if (req.getBranchId() != null) {
            branchRepository.findById(req.getBranchId())
                    .filter(branch -> branch.getTenantId().equals(TenantContext.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Branch", req.getBranchId()));
        }
        targetPolicy.requireAllowed(req.getIp(), req.getBranchId());
        DeviceSyncDTO.DeviceConfigDTO dto = new DeviceSyncDTO.DeviceConfigDTO();
        dto.setDeviceIp(req.getIp());
        dto.setUsername(req.getUsername());
        dto.setPassword(req.getPassword());
        dto.setDeviceName(req.getName());
        dto.setAnpr(req.getAnpr());
        if (req.getEnabled() != null) {
            dto.setStatus(req.getEnabled() ? "ACTIVE" : "INACTIVE");
        }
        return dto;
    }

    private DeviceConfig upsertBackendDevice(
            DeviceSyncDTO.DeviceConfigDTO isapiResult,
            Long branchId,
            String password,
            String anprRole) {
        DeviceConfig device = deviceConfigRepository.findByDeviceId(isapiResult.getDeviceId()).orElse(new DeviceConfig());
        if (anprRole != null) {
            if (!Boolean.TRUE.equals(isapiResult.getAnpr())) {
                throw new BadRequestException("ISAPI xidməti ANPR kamera rejimini təsdiqləmədi.");
            }
            if (device.getId() != null) throw new BadRequestException("Bu kamera artıq qeydiyyatdadır.");
            device.setAnpr(true);
            device.setDoorRole(anprRole);
            device.setAttendanceRoleSince(com.hic.util.AppTimeZone.now());
        }
        device.setDeviceId(isapiResult.getDeviceId());
        device.setDeviceName(isapiResult.getDeviceName());
        device.setDeviceIp(isapiResult.getDeviceIp());
        device.setUsername(isapiResult.getUsername());
        device.setStatus(isapiResult.getStatus());
        device.setOnline(isapiResult.isOnline());
        updateLastSuccessfulSync(device, isapiResult.getLastSyncTime());
        if (branchId != null) {
            device.setBranchId(branchId);
        }
        updateStoredPassword(device, password);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && device.getTenantId() == null) {
            device.setTenantId(tenantId);
        }
        return deviceConfigRepository.save(device);
    }

    private void updateBackendDevice(
            DeviceConfig device,
            DeviceSyncDTO.DeviceConfigDTO isapiResult,
            Long branchId,
            String password) {
        device.setDeviceName(isapiResult.getDeviceName());
        device.setDeviceIp(isapiResult.getDeviceIp());
        device.setUsername(isapiResult.getUsername());
        device.setStatus(isapiResult.getStatus());
        device.setOnline(isapiResult.isOnline());
        updateLastSuccessfulSync(device, isapiResult.getLastSyncTime());
        if (branchId != null) {
            device.setBranchId(branchId);
        }
        updateStoredPassword(device, password);
        deviceConfigRepository.save(device);
    }

    private void updateStoredPassword(DeviceConfig device, String password) {
        if (StringUtils.hasText(password)) {
            device.setPasswordEncrypted(encryptionUtil.encrypt(password.trim()));
        }
    }

    private void updateLastSuccessfulSync(DeviceConfig device, LocalDateTime candidate) {
        if (candidate != null
                && (device.getLastSyncTime() == null || candidate.isAfter(device.getLastSyncTime()))) {
            device.setLastSyncTime(candidate);
        }
    }

    private Long toIsapiId(String deviceId) {
        try {
            return Long.valueOf(deviceId);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid deviceId format: " + deviceId);
        }
    }

    private void lockDeviceTenant() {
        Long tenant = TenantContext.getTenantId();
        if (tenant != null) anprAreas.lockTenant(tenant);
    }

    private void reconcileAnprPair(DeviceConfig device) {
        if (device.isAnpr() && device.getBranchId() != null) {
            anprAreas.reconcilePair(device.getTenantId(), device.getBranchId());
        }
    }

    private DeviceConfig requireOwnedDevice(Long id) {
        DeviceConfig device = deviceConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", id));
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || !tenantId.equals(device.getTenantId())) {
            throw new ResourceNotFoundException("DeviceConfig", id);
        }
        return device;
    }
}
