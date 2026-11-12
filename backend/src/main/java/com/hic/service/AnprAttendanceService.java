package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO.AttendanceLogEntryDTO;
import com.hic.model.AttendanceLog;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.AppTimeZone;
import com.hic.util.ShiftTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.hic.service.AttendancePunchSelection.Direction.ENTRY;
import static com.hic.service.AttendancePunchSelection.Direction.EXIT;
import static com.hic.service.AttendancePunchSelection.Method.ANPR;

/** Stores resolved source events and projects a single session for each enabled area and work date. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnprAttendanceService {
    private static final int MIN_CONFIDENCE = 80;
    private final JdbcTemplate jdbc;
    private final AnprBridgeClient bridge;
    private final EmployeeVehicleService vehicles;
    private final EmployeeRepository employees;
    private final AttendanceLogRepository logs;
    private final AttendanceScheduleResolver schedules;
    private final AttendanceSessionPolicy sessionPolicy;

    record WorkDay(long area, long employee, LocalDate date) {}
    record CaptureCount(int read, int skipped) {}
    private record Source(long id, long device, LocalDateTime time, AttendancePunchSelection.Method method,
                          String direction) {}

    Map<Long, List<AnprAreaConfiguration.Period>> enabledAreas(long tenant) {
        return jdbc.query("SELECT area_id, starts_on, ends_on FROM anpr_attendance_area_periods WHERE tenant_id = ?", row -> {
            Map<Long, List<AnprAreaConfiguration.Period>> areas = new LinkedHashMap<>();
            while (row.next()) {
                areas.computeIfAbsent(row.getLong(1), ignored -> new ArrayList<>()).add(
                        new AnprAreaConfiguration.Period(row.getObject(2, LocalDate.class), row.getObject(3, LocalDate.class)));
            }
            return areas;
        }, tenant);
    }

    void lockTenant(long tenant) {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR NO KEY UPDATE", Long.class, tenant);
    }

    CaptureCount captureCamera(long tenant, DeviceConfig camera, long device, List<AnprAreaConfiguration.Period> periods,
                       OffsetDateTime start, OffsetDateTime end, Set<WorkDay> changed) {
        List<AnprBridgeClient.Capture> captures;
        try {
            captures = bridge.captures(device, start, end);
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not read ANPR captures from bridge device {}", device);
            return new CaptureCount(0, 0);
        }
        if (captures.isEmpty()) return new CaptureCount(0, 0);
        var existing = new java.util.HashSet<>(jdbc.queryForList("""
                SELECT source_id FROM anpr_attendance_punches
                WHERE tenant_id = ? AND bridge_device_id = ? AND method = 'ANPR'
                  AND occurred_at BETWEEN ? AND ?
                """, Long.class, tenant, device, AppTimeZone.toLocalDateTime(start), AppTimeZone.toLocalDateTime(end)));
        var unseen = captures.stream().filter(capture -> !existing.contains(capture.id())).toList();
        var result = persistCamera(tenant, camera, device, periods, unseen, null, null, changed);
        return new CaptureCount(captures.size(), result.skipped());
    }

    CaptureCount persistCamera(long tenant, DeviceConfig camera, long device, List<AnprAreaConfiguration.Period> periods,
                               List<AnprBridgeClient.Capture> captures, LocalDate archiveStart, LocalDate archiveEnd,
                               Set<WorkDay> changed) {
        boolean historical = archiveStart != null;
        int skipped = 0;
        for (var capture : captures) {
            if (capture.capturedAt() == null) {
                skipped++;
                continue;
            }
            LocalDateTime time = AppTimeZone.toLocalDateTime(capture.capturedAt());
            if (!historical && camera.getAttendanceRoleSince() != null && time.isBefore(camera.getAttendanceRoleSince())) {
                skipped++;
                continue;
            }
            String plate = EmployeeVehicleService.normalize(capture.plate());
            Long employeeId = plate == null ? null : vehicles.ownerAt(tenant, plate, time);
            Employee employee = employeeId == null ? null : employees.findByTenantIdAndId(tenant, employeeId).orElse(null);
            LocalDate workDate = employee == null ? time.toLocalDate()
                    : workDate(employee, time, camera.getDoorRole());
            if (historical && (workDate.isBefore(archiveStart) || workDate.isAfter(archiveEnd))) {
                skipped++;
                continue;
            }
            String rejection = rejection(periods, workDate, plate, capture.confidence(), employee,
                    historical, capture.archived());
            if (rejection != null) skipped++;
            WorkDay day = rejection == null ? new WorkDay(camera.getBranchId(), employeeId, workDate) : null;
            boolean added = insert(tenant, camera.getBranchId(), employeeId, workDate, device, camera.getDeviceName(),
                    capture.id(), ANPR, camera.getDoorRole(), time, capture.plate(), capture.confidence(), rejection);
            if (historical && day != null) {
                restoreRejectedCamera(tenant, device, capture.id(), employeeId, workDate);
                changed.add(day);
            } else if (day != null && added) {
                changed.add(day);
            }
        }
        return new CaptureCount(captures.size(), skipped);
    }

    private void restoreRejectedCamera(long tenant, long device, long sourceId, long employee, LocalDate date) {
        jdbc.update("""
                UPDATE anpr_attendance_punches SET employee_id = ?, work_date = ?, rejection = NULL
                WHERE tenant_id = ? AND bridge_device_id = ? AND source_id = ? AND method = 'ANPR'
                    AND rejection IN ('BEFORE_ACTIVATION', 'OUTSIDE_ACTIVATION', 'UNASSIGNED_PLATE', 'LOW_CONFIDENCE')
                """, employee, date, tenant, device, sourceId);
    }

    boolean captureTerminal(long tenant, Employee employee, Long area, List<AnprAreaConfiguration.Period> periods, String role,
                            AttendanceLogEntryDTO punch, Set<WorkDay> changed) {
        if (periods.isEmpty() || punch.getId() == null) return false;
        LocalDateTime time = AppTimeZone.toLocalDateTime(punch.getPunchTime());
        LocalDate date = workDate(employee, time, role);
        if (!enabledOn(periods, date)) return false;
        var method = terminalMethod(punch.getVerificationMethod());
        if (insert(tenant, area, employee.getId(), date, punch.getDeviceId(), punch.getDeviceName(),
                punch.getId(), method, role, time, null, null, null)) {
            changed.add(new WorkDay(area, employee.getId(), date));
        }
        return true;
    }

    AttendanceLog project(long tenant, WorkDay day) {
        return project(tenant, day, null);
    }

    AttendanceLog projectArchive(long tenant, WorkDay day) {
        var legacy = logs.findArchiveLegacySessions(tenant, day.employee(), day.area(),
                day.date().atStartOfDay(), day.date().plusDays(1).atStartOfDay());
        if (legacy.size() > 1 || legacy.stream().anyMatch(log ->
                log.getAreaSnapshotId() == null || Boolean.TRUE.equals(log.getManualOverride()))) return null;
        AttendanceLog existing = legacy.isEmpty() ? null : legacy.get(0);
        if (existing != null) {
            preserveLegacyEndpoint(tenant, day, existing, true);
            preserveLegacyEndpoint(tenant, day, existing, false);
        }
        return project(tenant, day, existing);
    }

    private void preserveLegacyEndpoint(long tenant, WorkDay day, AttendanceLog session, boolean entry) {
        LocalDateTime time = entry ? session.getCheckInTime() : session.getCheckOutTime();
        if (time == null) return;
        String device = entry || session.getDoorId() == null ? session.getDeviceId()
                : session.getDoorId().substring(session.getDoorId().lastIndexOf(':') + 1);
        long bridgeId = device != null && device.matches("[0-9]{1,18}") ? Long.parseLong(device) : 0;
        long source = -Math.multiplyExact(session.getId(), 2) - (entry ? 0 : 1);
        insert(tenant, day.area(), day.employee(), day.date(), bridgeId, null, source,
                terminalMethod(entry ? session.getEntryMethod() : session.getExitMethod()),
                entry ? "ENTRY" : "EXIT", time, null, null, null);
    }

    private AttendanceLog project(long tenant, WorkDay day, AttendanceLog legacy) {
        Employee employee = employees.findByTenantIdAndId(tenant, day.employee()).orElse(null);
        if (employee == null) return null;
        var session = logs.findByTenantIdAndEmployeeIdAndAnprAreaIdAndAnprWorkDate(
                tenant, day.employee(), day.area(), day.date()).orElse(null);
        if (legacy != null && session != null) return null;
        if (session != null && Boolean.TRUE.equals(session.getManualOverride())) return null;
        var schedule = schedules.resolve(employee, day.date());
        LocalDateTime deadline = session != null && session.getAnprDeadline() != null ? session.getAnprDeadline()
                : deadline(employee, day.date(), schedule);
        String shiftType = session != null ? session.getShiftType() : schedule.shiftType();
        boolean sameMethod = deadline.isAfter(day.date().plusDays(1).atStartOfDay())
                || overnight(schedule)
                || ShiftTypes.NIGHT.equals(ShiftTypes.canonical(shiftType));
        var selection = AttendancePunchSelection.select(candidates(tenant, day, sameMethod, deadline), sameMethod);
        if (selection.isEmpty()) return null;
        var entry = selection.get().entry();
        var exit = selection.get().exit();
        if (legacy != null && legacy.getCheckOutTime() != null && exit == null) return null;
        if (legacy != null) {
            if (!entry.time().equals(legacy.getCheckInTime()) && logs.findByTenantIdAndEmployeeIdAndCheckInTime(
                    tenant, day.employee(), entry.time()).isPresent()) return null;
            session = legacy;
            session.setAnprAreaId(day.area());
            session.setAnprWorkDate(day.date());
            session.setLastEntryTime(null);
            session.setLastEntryMethod(null);
        }
        if (session == null) {
            // Preserve an existing manual or legacy row that already owns the same timestamp.
            if (logs.findByTenantIdAndEmployeeIdAndCheckInTime(tenant, day.employee(), entry.time()).isPresent()) return null;
            session = new AttendanceLog();
            session.setTenantId(tenant);
            session.setEmployeeId(day.employee());
            session.setAnprAreaId(day.area());
            session.setAnprWorkDate(day.date());
            session.setShiftType(schedule.shiftType());
            session.setTimetableId(schedule.timetableId());
            session.setEventType("ANPR_AREA_SESSION");
            session.setVerificationMethod("ISAPI_PUNCH");
        } else if (Objects.equals(session.getAnprEntryId(), entry.id())
                && Objects.equals(session.getAnprExitId(), exit == null ? null : exit.id())) {
            return null;
        } else if (!entry.time().equals(session.getCheckInTime())
                && logs.findByTenantIdAndEmployeeIdAndCheckInTime(tenant, day.employee(), entry.time()).isPresent()) {
            return null;
        }
        session.setAnprEntryId(entry.id());
        session.setAnprDeadline(deadline);
        session.setAnprExitId(exit == null ? null : exit.id());
        session.setCheckInTime(entry.time());
        session.setCheckOutTime(exit == null ? null : exit.time());
        session.setDeviceId(Long.toString(entry.deviceId()));
        session.setDoorId(entry.deviceId() + ":" + (exit == null ? "null" : exit.deviceId()));
        session.setEntryMethod(entry.method().name());
        session.setExitMethod(exit == null ? null : exit.method().name());
        session.setStatus(sessionPolicy.effectiveStatus(employee, session));
        return logs.save(session);
    }

    private List<AttendancePunchSelection.Punch> candidates(long tenant, WorkDay day, boolean sameMethod, LocalDateTime deadline) {
        List<Source> punches = jdbc.query("""
                SELECT id, bridge_device_id, occurred_at, method, direction FROM anpr_attendance_punches
                WHERE tenant_id = ? AND area_id = ? AND employee_id = ? AND work_date = ? AND rejection IS NULL
                ORDER BY occurred_at, id
                """, (row, index) -> new Source(row.getLong(1), row.getLong(2), row.getObject(3, LocalDateTime.class),
                AttendancePunchSelection.Method.valueOf(row.getString(4)), row.getString(5)),
                tenant, day.area(), day.employee(), day.date());
        List<AttendancePunchSelection.Punch> candidates = new ArrayList<>();
        Map<AttendancePunchSelection.Method, List<Source>> combined = new LinkedHashMap<>();
        for (Source punch : punches) {
            if (!punch.time().isBefore(deadline)) continue;
            switch (punch.direction()) {
                case "ENTRY" -> candidates.add(candidate(punch, ENTRY));
                case "EXIT" -> candidates.add(candidate(punch, EXIT));
                case "BOTH" -> combined.computeIfAbsent(sameMethod ? punch.method() : null,
                        ignored -> new ArrayList<>()).add(punch);
                default -> throw new IllegalStateException("Unknown attendance direction");
            }
        }
        for (List<Source> scans : combined.values()) {
            Source first = scans.get(0);
            Source last = scans.get(scans.size() - 1);
            if (first.time().toLocalDate().equals(day.date())) candidates.add(candidate(first, ENTRY));
            if (last.time().isAfter(first.time())) candidates.add(candidate(last, EXIT));
        }
        return candidates;
    }

    LocalDate workDate(Employee employee, LocalDateTime time, String direction) {
        LocalDate date = time.toLocalDate();
        LocalDate previous = date.minusDays(1);
        var priorSchedule = schedules.resolve(employee, previous);
        if (!overnight(priorSchedule)) return date;
        if ("ENTRY".equals(direction)) {
            var today = schedules.resolve(employee, date);
            return time.toLocalTime().isBefore(priorSchedule.endTime())
                    && (today.startTime() == null || time.toLocalTime().isBefore(today.startTime())) ? previous : date;
        }
        return time.isBefore(deadline(employee, previous, priorSchedule)) ? previous : date;
    }

    private LocalDateTime deadline(Employee employee, LocalDate workDate, AttendanceScheduleResolver.DaySchedule schedule) {
        return deadline(workDate, schedule, overnight(schedule) ? schedules.resolve(employee, workDate.plusDays(1)) : null);
    }

    static LocalDateTime deadline(LocalDate workDate, AttendanceScheduleResolver.DaySchedule priorSchedule,
                                  AttendanceScheduleResolver.DaySchedule next) {
        LocalDate date = workDate.plusDays(1);
        if (!overnight(priorSchedule)) return date.atStartOfDay();
        var scheduledEnd = date.atTime(priorSchedule.endTime());
        long gap = Duration.between(scheduledEnd, date.atTime(next == null || next.startTime() == null
                ? priorSchedule.startTime() : next.startTime())).toMinutes();
        long grace = Math.min(AttendanceSessionPolicy.SCHEDULED_CLOSE_GRACE_MINUTES, Math.max(0, gap / 2));
        return scheduledEnd.plusMinutes(grace);
    }

    static boolean overnight(AttendanceScheduleResolver.DaySchedule schedule) {
        return schedule != null && !schedule.flexible() && schedule.startTime() != null && schedule.endTime() != null
                && !schedule.endTime().isAfter(schedule.startTime());
    }

    private static AttendancePunchSelection.Punch candidate(Source source, AttendancePunchSelection.Direction direction) {
        return new AttendancePunchSelection.Punch(source.id(), source.device(), source.time(), source.method(), direction);
    }

    private boolean insert(long tenant, long area, Long employee, LocalDate date, long device, String name,
                           long sourceId, AttendancePunchSelection.Method method, String role, LocalDateTime time,
                           String plate, Integer confidence, String rejection) {
        return jdbc.update("""
                INSERT INTO anpr_attendance_punches (tenant_id, area_id, employee_id, work_date, bridge_device_id,
                    device_name, source_id, method, direction, occurred_at, plate, confidence, rejection)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, bridge_device_id, source_id) DO NOTHING
                """, tenant, area, employee, date, device, name, sourceId, method.name(), role, time,
                plate, confidence, rejection) > 0;
    }

    private static boolean enabledOn(List<AnprAreaConfiguration.Period> periods, LocalDate date) {
        return periods.stream().anyMatch(period -> period.contains(date));
    }

    private static String rejection(List<AnprAreaConfiguration.Period> periods, LocalDate date, String plate,
                                    Integer confidence, Employee employee, boolean historical, boolean archived) {
        if (!historical && !enabledOn(periods, date)) return "OUTSIDE_ACTIVATION";
        if (plate == null) return "UNREADABLE_PLATE";
        if (confidence == null ? !(historical && archived) : confidence < MIN_CONFIDENCE) return "LOW_CONFIDENCE";
        if (employee == null || (!historical && employee.getEmploymentStatus() == Employee.EmploymentStatus.TERMINATED)) {
            return "UNASSIGNED_PLATE";
        }
        return null;
    }

    private static AttendancePunchSelection.Method terminalMethod(String method) {
        if ("FACE".equals(method)) return AttendancePunchSelection.Method.FACE;
        if ("CARD".equals(method)) return AttendancePunchSelection.Method.CARD;
        return AttendancePunchSelection.Method.ACCESS;
    }
}
