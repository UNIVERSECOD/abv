package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.exception.BadRequestException;
import com.hic.service.UserScopeService;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/transport-report")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class TransportReportController {
    private static final int PAGE_SIZE = 50;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserScopeService userScope;

    public record Passage(long id, String plate, LocalDateTime time, String direction, Integer confidence,
                          Long employeeId, String employeeName, String area, String camera, String result) {}
    public record PassagePage(List<Passage> rows, boolean hasMore) {}

    @GetMapping
    public ApiResponse<PassagePage> list(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(required = false) Long areaId, @RequestParam(required = false) String employee,
            @RequestParam(required = false) String plate, @RequestParam(defaultValue = "0") int page) {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366
                || page < 0 || page > 100_000 || (plate != null && plate.length() > 64)
                || (employee != null && employee.length() > 120)) {
            throw new BadRequestException("Hesabat filtrlərini yoxlayın.");
        }
        Long area = userScope.resolveBranchScope(areaId);
        var parameters = new MapSqlParameterSource()
                .addValue("tenant", tenant).addValue("from", from.atStartOfDay())
                .addValue("to", to.plusDays(1).atStartOfDay()).addValue("limit", PAGE_SIZE + 1)
                .addValue("offset", page * PAGE_SIZE);
        StringBuilder filter = new StringBuilder();
        if (area != null) {
            filter.append(" AND p.area_id = :area");
            parameters.addValue("area", area);
        }
        if (employee != null && !employee.isBlank()) {
            filter.append(" AND strpos(lower(concat_ws(' ', e.first_name, e.last_name, e.employee_id)), lower(:employee)) > 0");
            parameters.addValue("employee", employee.strip());
        }
        if (plate != null && !plate.isBlank()) {
            filter.append(" AND regexp_replace(upper(p.plate), '[^A-Z0-9]', '', 'g') = :plate");
            parameters.addValue("plate", plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
        }
        var rows = jdbc.query("""
                SELECT p.id, p.plate, p.occurred_at, p.direction, p.confidence, p.employee_id,
                    CASE WHEN e.id IS NOT NULL THEN concat_ws(' ', e.first_name, e.last_name) END AS employee_name,
                    b.name AS area, p.device_name,
                    CASE WHEN p.rejection IS NOT NULL THEN p.rejection
                         WHEN p.id IN (a.anpr_entry_id, a.anpr_exit_id) THEN 'SELECTED'
                         WHEN a.id IS NULL OR (p.direction = 'EXIT' AND a.check_out_time IS NULL) THEN 'UNMATCHED'
                         ELSE 'NOT_SELECTED' END AS result
                FROM anpr_attendance_punches p
                LEFT JOIN employees e ON e.tenant_id = p.tenant_id AND e.id = p.employee_id
                LEFT JOIN branches b ON b.tenant_id = p.tenant_id AND b.id = p.area_id
                LEFT JOIN attendance_logs a ON a.tenant_id = p.tenant_id AND a.employee_id = p.employee_id
                    AND a.anpr_area_id = p.area_id AND a.anpr_work_date = p.work_date
                WHERE p.tenant_id = :tenant AND p.method = 'ANPR' AND p.occurred_at >= :from AND p.occurred_at < :to
                """ + filter + " ORDER BY p.occurred_at DESC, p.id DESC LIMIT :limit OFFSET :offset", parameters,
                (row, index) -> new Passage(row.getLong(1), row.getString(2), row.getObject(3, LocalDateTime.class),
                        row.getString(4), row.getObject(5, Integer.class), row.getObject(6, Long.class),
                        row.getString(7), row.getString(8), row.getString(9), row.getString(10)));
        return ApiResponse.success(new PassagePage(rows.subList(0, Math.min(PAGE_SIZE, rows.size())), rows.size() > PAGE_SIZE));
    }
}
