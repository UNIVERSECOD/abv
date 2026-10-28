package com.hic.service;

import com.hic.dto.DeviceSyncDTO;
import com.hic.dto.DoorDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.DeviceConfig;
import com.hic.model.Door;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.DoorRepository;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private final DeviceConfigRepository deviceConfigRepository;
    private final DeviceSyncService deviceSyncService;
    private final DoorRepository doorRepository;

    public List<DeviceSyncDTO.DeviceConfigDTO> getAll() {
        Long tenantId = TenantContext.getTenantId();
        List<DeviceConfig> devices = tenantId != null
                ? deviceConfigRepository.findByTenantId(tenantId)
                : deviceConfigRepository.findAll();
        return devices.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public DeviceSyncDTO.DeviceConfigDTO getById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        DeviceConfig device = deviceConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", id));
        if (tenantId != null && device.getTenantId() != null && !tenantId.equals(device.getTenantId())) {
            throw new BadRequestException("Device does not belong to your tenant");
        }
        return toDTO(device);
    }

    @Transactional
    public DeviceSyncDTO.SyncResultDTO syncFromIsapi() {
        log.info("Syncing devices from ISAPI to database");
        Long tenantId = TenantContext.getTenantId();
        
        try {
            List<DeviceSyncDTO.DeviceConfigDTO> isapiDevices = deviceSyncService.getAllDevices(null);
            int syncedCount = 0;
            
            for (DeviceSyncDTO.DeviceConfigDTO isapiDevice : isapiDevices) {
                DeviceConfig existing = deviceConfigRepository.findByDeviceId(isapiDevice.getDeviceId()).orElse(null);
                
                if (existing == null) {
                    DeviceConfig newDevice = new DeviceConfig();
                    newDevice.setDeviceId(isapiDevice.getDeviceId());
                    newDevice.setDeviceName(isapiDevice.getDeviceName());
                    newDevice.setDeviceIp(isapiDevice.getDeviceIp());
                    newDevice.setUsername(isapiDevice.getUsername());
                    newDevice.setStatus(isapiDevice.getStatus());
                    newDevice.setOnline(isapiDevice.isOnline());
                    updateLastSuccessfulSync(newDevice, isapiDevice.getLastSyncTime());
                    newDevice.setTenantId(tenantId);
                    deviceConfigRepository.save(newDevice);
                    syncedCount++;
                    log.info("Created new device: {} ({})", isapiDevice.getDeviceName(), isapiDevice.getDeviceId());
                } else {
                    if (tenantId != null && existing.getTenantId() != null && !tenantId.equals(existing.getTenantId())) {
                        continue;
                    }
                    existing.setDeviceName(isapiDevice.getDeviceName());
                    existing.setDeviceIp(isapiDevice.getDeviceIp());
                    existing.setUsername(isapiDevice.getUsername());
                    existing.setStatus(isapiDevice.getStatus());
                    existing.setOnline(isapiDevice.isOnline());
                    updateLastSuccessfulSync(existing, isapiDevice.getLastSyncTime());
                    if (tenantId != null && existing.getTenantId() == null) {
                        existing.setTenantId(tenantId);
                    }
                    deviceConfigRepository.save(existing);
                    syncedCount++;
                    log.info("Updated device: {} ({}) status={} lastSyncTime={}", existing.getDeviceName(), existing.getDeviceId(), existing.getStatus(), existing.getLastSyncTime());
                }
            }
            
            log.info("Device sync completed: {} devices synced", syncedCount);
            return new DeviceSyncDTO.SyncResultDTO(true, "Synced " + syncedCount + " devices from ISAPI", syncedCount);
        } catch (Exception e) {
            log.error("Failed to sync devices from ISAPI", e);
            return new DeviceSyncDTO.SyncResultDTO(false, "Sync failed: " + e.getMessage(), 0);
        }
    }

    @Transactional
    public DeviceSyncDTO.DeviceConfigDTO assignDoor(Long deviceConfigId, DoorDTO.AssignDoorRequest dto) {
        Long tenantId = TenantContext.getTenantId();
        DeviceConfig device = deviceConfigRepository.findById(deviceConfigId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", deviceConfigId));
        if (tenantId != null && device.getTenantId() != null && !tenantId.equals(device.getTenantId())) {
            throw new BadRequestException("Device does not belong to your tenant");
        }
        String role = dto.getRole() != null ? dto.getRole().trim().toUpperCase() : null;
        if (role != null && !"ENTRY".equals(role) && !"EXIT".equals(role) && !"BOTH".equals(role)) {
            throw new BadRequestException("Rol Giriş, Çıxış və ya Giriş və çıxış olmalıdır.");
        }
        if ("BOTH".equals(role) && (tenantId == null || device.getBranchId() == null)) {
            throw new BadRequestException("Giriş və çıxış rejimi üçün ərazi seçin.");
        }
        if (!java.util.Objects.equals(role, device.getDoorRole())
                && ("BOTH".equals(role) || "BOTH".equals(device.getDoorRole()))) {
            device.setAttendanceRoleSince(com.hic.util.AppTimeZone.now());
        }

        if (dto.getDoorId() == null) {
            device.setDoorId(null);
            device.setDoorRole(role);
            deviceConfigRepository.save(device);
            return toDTO(device);
        }
        Door door = doorRepository.findById(dto.getDoorId())
                .orElseThrow(() -> new ResourceNotFoundException("Door", dto.getDoorId()));
        if (tenantId != null && !tenantId.equals(door.getTenantId())) {
            throw new BadRequestException("Door does not belong to your tenant");
        }
        if (device.getBranchId() != null && !device.getBranchId().equals(door.getBranchId())) {
            throw new BadRequestException("Device branch does not match door branch");
        }
        // Check if another device already has this role on the same door
        deviceConfigRepository.findByDoorIdAndDoorRole(dto.getDoorId(), role)
                .ifPresent(existing -> {
                    if (!existing.getId().equals(deviceConfigId)) {
                        throw new BadRequestException("Door already has a " + role + " device assigned");
                    }
                });
        device.setDoorId(dto.getDoorId());
        device.setDoorRole(role);
        DeviceConfig saved = deviceConfigRepository.save(device);
        return toDTO(saved);
    }

    private DeviceSyncDTO.DeviceConfigDTO toDTO(DeviceConfig device) {
        DeviceSyncDTO.DeviceConfigDTO dto = new DeviceSyncDTO.DeviceConfigDTO();
        dto.setId(device.getId());
        dto.setDeviceId(device.getDeviceId());
        dto.setDeviceName(device.getDeviceName());
        dto.setDeviceIp(device.getDeviceIp());
        dto.setDevicePort(device.getDevicePort());
        dto.setUsername(device.getUsername());
        dto.setPassword(null);
        dto.setBranchId(device.getBranchId());
        dto.setDoorId(device.getDoorId());
        dto.setDoorRole(device.getDoorRole());
        dto.setStatus(device.getStatus());
        dto.setOnline(device.isOnline());
        dto.setLastSyncTime(device.getLastSyncTime());
        return dto;
    }

    private void updateLastSuccessfulSync(DeviceConfig device, java.time.LocalDateTime candidate) {
        if (candidate != null
                && (device.getLastSyncTime() == null || candidate.isAfter(device.getLastSyncTime()))) {
            device.setLastSyncTime(candidate);
        }
    }
}
