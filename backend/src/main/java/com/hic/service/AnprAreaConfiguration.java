package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class AnprAreaConfiguration {
    private final JdbcTemplate jdbc;
    private final DeviceConfigRepository devices;

    record Period(LocalDate start, LocalDate end) {
        boolean contains(LocalDate date) {
            return !date.isBefore(start) && (end == null || date.isBefore(end));
        }
    }

    public void lockTenant(long tenant) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR NO KEY UPDATE", Long.class, tenant);
    }

    public String validate(Long tenant, Long area, String requestedRole, String endpoint, DeviceConfig existing) {
        String role = requestedRole != null ? requestedRole.strip().toUpperCase(Locale.ROOT)
                : existing != null ? existing.getDoorRole() : null;
        if (tenant == null || area == null || (!"ENTRY".equals(role) && !"EXIT".equals(role))) {
            throw new BadRequestException("ANPR kamera üçün ərazi və Giriş və ya Çıxış rolu seçin.");
        }
        if (existing != null && !Objects.equals(existing.getBranchId(), area)) {
            throw new BadRequestException("ANPR kameranın ərazisini dəyişmək üçün onu yenidən qeydiyyatdan keçirin.");
        }
        String normalized = com.hic.util.DeviceAddress.normalize(endpoint);
        for (DeviceConfig camera : devices.findByTenantId(tenant)) {
            if (existing != null && Objects.equals(existing.getId(), camera.getId())) continue;
            if (sameEndpoint(camera.getDeviceIp(), normalized)) {
                throw new BadRequestException("Bu kamera ünvanı artıq qeydiyyatdadır.");
            }
            if (camera.isAnpr() && area.equals(camera.getBranchId()) && role.equals(camera.getDoorRole())) {
                throw new BadRequestException("Bu ərazi üçün həmin rolda ANPR kamera artıq mövcuddur.");
            }
        }
        return role;
    }

    private static boolean sameEndpoint(String address, String normalized) {
        try {
            return normalized.equals(com.hic.util.DeviceAddress.normalize(address));
        } catch (IllegalArgumentException invalidLegacyAddress) {
            return false;
        }
    }

    /** The caller holds the tenant lock in the transaction that changed the camera. */
    public void reconcilePair(long tenant, long area) {
        var active = devices.findByTenantIdAndBranchId(tenant, area).stream()
                .filter(device -> device.isAnpr() && "ACTIVE".equals(device.getStatus()))
                .toList();
        boolean complete = active.size() == 2
                && active.stream().filter(device -> "ENTRY".equals(device.getDoorRole())).count() == 1
                && active.stream().filter(device -> "EXIT".equals(device.getDoorRole())).count() == 1
                && active.stream().allMatch(device -> validBridgeId(device.getDeviceId()))
                && Long.parseLong(active.get(0).getDeviceId()) != Long.parseLong(active.get(1).getDeviceId());
        LocalDate effectiveDate = AppTimeZone.today().plusDays(1);
        if (complete) {
            jdbc.update("""
                    INSERT INTO anpr_attendance_area_periods (tenant_id, area_id, starts_on)
                    SELECT ?, ?, ? WHERE NOT EXISTS (
                        SELECT 1 FROM anpr_attendance_area_periods WHERE tenant_id = ? AND area_id = ? AND ends_on IS NULL
                    )
                    ON CONFLICT (tenant_id, area_id, starts_on) DO UPDATE SET ends_on = NULL
                    """, tenant, area, effectiveDate, tenant, area);
        } else {
            jdbc.update("""
                    UPDATE anpr_attendance_area_periods SET ends_on = GREATEST(starts_on, ?)
                    WHERE tenant_id = ? AND area_id = ? AND ends_on IS NULL
                    """, effectiveDate, tenant, area);
        }
    }

    private static boolean validBridgeId(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
