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
import com.hic.service.DeviceSyncService;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.HikDeviceUserImportService;
import com.hic.util.EncryptionUtil;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResponse<DeviceSyncDTO.SyncResultDTO>> syncFromIsapi() {
        return ResponseEntity.ok(ApiResponse.success(deviceService.syncFromIsapi()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> create(
            @RequestBody DeviceSyncDTO.DeviceUpsertRequest req) {
        DeviceSyncDTO.DeviceConfigDTO dto = toConfigDTO(req);
        DeviceSyncDTO.DeviceConfigDTO isapiResult = deviceSyncService.createDevice(dto);
        DeviceConfig backendDevice = upsertBackendDevice(isapiResult, req.getBranchId(), req.getPassword());
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(backendDevice.getId())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> update(
            @PathVariable Long id,
            @RequestBody DeviceSyncDTO.DeviceUpsertRequest req) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        DeviceSyncDTO.DeviceConfigDTO dto = toConfigDTO(req);
        DeviceSyncDTO.DeviceConfigDTO isapiResult = deviceSyncService.updateDevice(
                toIsapiId(backendDevice.getDeviceId()), dto);
        updateBackendDevice(backendDevice, isapiResult, req.getBranchId(), req.getPassword());
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        try {
            deviceSyncService.deleteDevice(toIsapiId(backendDevice.getDeviceId()));
        } catch (Exception e) {
            // ISAPI delete may fail if device already removed there; still remove backend record
        }
        deviceConfigRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/{id}/enabled")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceConfigDTO>> updateEnabled(
            @PathVariable Long id,
            @RequestBody DeviceSyncDTO.DeviceEnabledDTO dto) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        DeviceSyncDTO.DeviceConfigDTO isapiResult = deviceSyncService.updateEnabled(
                toIsapiId(backendDevice.getDeviceId()), dto);
        backendDevice.setStatus(isapiResult.getStatus());
        backendDevice.setOnline(isapiResult.isOnline());
        updateLastSuccessfulSync(backendDevice, isapiResult.getLastSyncTime());
        deviceConfigRepository.save(backendDevice);
        return ResponseEntity.ok(ApiResponse.success(deviceService.getById(id)));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<DeviceSyncDTO.DeviceRuntimeDTO>> start(@PathVariable Long id) {
        DeviceConfig backendDevice = requireOwnedDevice(id);
        return ResponseEntity.ok(ApiResponse.success(
                deviceSyncService.startDevice(toIsapiId(backendDevice.getDeviceId()))));
    }

    @PostMapping("/{id}/stop")
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
    public ResponseEntity<ApiResponse<HikUserInfoSearchDTO.ImportResultDTO>> importUsers(
            @PathVariable Long id) {
        HikUserInfoSearchDTO.ImportResultDTO result = hikDeviceUserImportService.importUsersFromDevice(id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private DeviceSyncDTO.DeviceConfigDTO toConfigDTO(DeviceSyncDTO.DeviceUpsertRequest req) {
        if (req.getBranchId() != null) {
            branchRepository.findById(req.getBranchId())
                    .filter(branch -> branch.getTenantId().equals(TenantContext.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Branch", req.getBranchId()));
        }
        DeviceSyncDTO.DeviceConfigDTO dto = new DeviceSyncDTO.DeviceConfigDTO();
        dto.setDeviceIp(req.getIp());
        dto.setUsername(req.getUsername());
        dto.setPassword(req.getPassword());
        dto.setDeviceName(req.getName());
        if (req.getEnabled() != null) {
            dto.setStatus(req.getEnabled() ? "ACTIVE" : "INACTIVE");
        }
        return dto;
    }

    private DeviceConfig upsertBackendDevice(
            DeviceSyncDTO.DeviceConfigDTO isapiResult,
            Long branchId,
            String password) {
        DeviceConfig device = deviceConfigRepository.findByDeviceId(isapiResult.getDeviceId()).orElse(new DeviceConfig());
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
