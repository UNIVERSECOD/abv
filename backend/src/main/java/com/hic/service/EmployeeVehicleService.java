package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.repository.EmployeeRepository;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmployeeVehicleService {
    private final JdbcTemplate jdbc;
    private final EmployeeRepository employees;

    public record Vehicle(long id, String plate, LocalDateTime validFrom) {}
    private static final RowMapper<Vehicle> VEHICLE = (row, index) ->
            new Vehicle(row.getLong(1), row.getString(2), row.getObject(3, LocalDateTime.class));

    public List<Vehicle> list(long tenant, long employee) {
        requireEmployee(tenant, employee);
        return jdbc.query("""
                SELECT id, plate, valid_from FROM employee_vehicle_plates
                WHERE tenant_id = ? AND employee_id = ? AND valid_until IS NULL ORDER BY plate
                """, VEHICLE, tenant, employee);
    }

    @Transactional
    public Vehicle add(long tenant, long employee, String value, LocalDate effectiveFrom) {
        lockAssignments(tenant);
        var person = employees.findByTenantIdAndId(tenant, employee)
                .orElseThrow(() -> new ResourceNotFoundException("Əməkdaş", employee));
        if (person.getEmploymentStatus() == com.hic.model.Employee.EmploymentStatus.TERMINATED) {
            throw new BadRequestException("Xitam verilmiş əməkdaşa nəqliyyat təyin edilə bilməz.");
        }
        String plate = normalize(value);
        if (plate == null) throw new BadRequestException("Etibarlı avtomobil nömrəsi daxil edin.");
        LocalDateTime now = AppTimeZone.now();
        LocalDateTime start = effectiveFrom == null ? now : effectiveFrom.atStartOfDay();
        if (start.isAfter(now)) throw new BadRequestException("Qüvvəyə minmə tarixi gələcəkdə ola bilməz.");
        if (effectiveFrom != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM employee_vehicle_plates
                WHERE tenant_id = ? AND plate = ? AND valid_until > ?)
                """, Boolean.class, tenant, plate, start))) {
            throw new BadRequestException("Seçilmiş tarix nömrənin əvvəlki təyinatı ilə üst-üstə düşür.");
        }
        var inserted = jdbc.query("""
                INSERT INTO employee_vehicle_plates (tenant_id, employee_id, plate, valid_from)
                VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, plate) WHERE valid_until IS NULL
                DO UPDATE SET valid_from = LEAST(employee_vehicle_plates.valid_from, EXCLUDED.valid_from)
                WHERE ? AND employee_vehicle_plates.employee_id = EXCLUDED.employee_id
                RETURNING id, plate, valid_from
                """, VEHICLE, tenant, employee, plate, start, effectiveFrom != null);
        if (inserted.isEmpty()) throw new BadRequestException("Bu nömrə artıq bir əməkdaşa təyin edilib.");
        if (effectiveFrom != null) {
            jdbc.update("""
                    UPDATE anpr_archive_sync_progress
                    SET next_day = LEAST(next_day, ?), next_attempt_at = ?, lease_id = NULL
                    WHERE tenant_id = ? AND next_day >= ?
                    """, effectiveFrom, AppTimeZone.nowOffset(), tenant, effectiveFrom);
        }
        return inserted.get(0);
    }

    @Transactional
    public void retire(long tenant, long employee, long id) {
        requireEmployee(tenant, employee);
        lockAssignments(tenant);
        int changed = jdbc.update("""
                UPDATE employee_vehicle_plates SET valid_until = GREATEST(valid_from, ?)
                WHERE tenant_id = ? AND employee_id = ? AND id = ? AND valid_until IS NULL
                """, AppTimeZone.now(), tenant, employee, id);
        if (changed == 0) throw new ResourceNotFoundException("Nəqliyyat nömrəsi", id);
    }

    public List<Vehicle> retired(long tenant, long employee) {
        requireEmployee(tenant, employee);
        return jdbc.query("""
                SELECT v.id, v.plate, v.valid_from FROM employee_vehicle_plates v
                WHERE v.tenant_id = ? AND v.employee_id = ? AND v.valid_until IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM employee_vehicle_plates newer
                    WHERE newer.tenant_id = v.tenant_id AND newer.plate = v.plate
                      AND (newer.valid_until IS NULL OR newer.valid_until > v.valid_until
                           OR (newer.valid_until = v.valid_until AND newer.id > v.id))
                  )
                ORDER BY v.valid_until DESC, v.id DESC
                """, VEHICLE, tenant, employee);
    }

    void retireAll(long tenant, long employee) {
        jdbc.update("""
                UPDATE employee_vehicle_plates SET valid_until = GREATEST(valid_from, ?)
                WHERE tenant_id = ? AND employee_id = ? AND valid_until IS NULL
                """, AppTimeZone.now(), tenant, employee);
    }

    Long ownerAt(long tenant, String plate, LocalDateTime time) {
        var ids = jdbc.queryForList("""
                SELECT employee_id FROM employee_vehicle_plates
                WHERE tenant_id = ? AND plate = ? AND valid_from <= ?
                  AND (valid_until IS NULL OR valid_until > ?) LIMIT 2
                """, Long.class, tenant, plate, time, time);
        return ids.size() == 1 ? ids.get(0) : null;
    }

    static String normalize(String value) {
        if (value == null || value.length() > 64) return null;
        String plate = value.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "");
        if (!plate.matches("[A-Z0-9]{2,32}") || plate.equals("UNKNOWN") || plate.equals("NOPLATE")
                || plate.chars().allMatch(c -> c == 'X')) return null;
        return plate;
    }

    void lockAssignments(long tenant) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR NO KEY UPDATE", Long.class, tenant);
    }

    private void requireEmployee(long tenant, long employee) {
        employees.findByTenantIdAndId(tenant, employee)
                .orElseThrow(() -> new ResourceNotFoundException("Əməkdaş", employee));
    }
}
