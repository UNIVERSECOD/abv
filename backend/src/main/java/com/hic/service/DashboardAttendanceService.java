package com.hic.service;

import com.hic.dto.DashboardAttendanceDTO;
import com.hic.model.*;
import com.hic.model.DailyAttendanceSummary.AttendanceStatus;
import com.hic.repository.*;
import com.hic.util.AppTimeZone;
import com.hic.util.ShiftTypes;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardAttendanceService {
    private final EmployeeRepository employees;
    private final BranchRepository branches;
    private final AttendanceLogRepository logs;
    private final AttendanceService attendance;
    private final AttendanceScheduleResolver scheduleResolver;
    private final UserScopeService userScope;

    public DashboardAttendanceDTO today() { return at(AppTimeZone.now()); }

    DashboardAttendanceDTO at(LocalDateTime now) {
        Long tenant = Objects.requireNonNull(TenantContext.getTenantId(), "Tenant context is required");
        Long scope = userScope.resolveBranchScope(null);
        LocalDate date = now.toLocalDate();
        List<Employee> selected = employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenant).stream()
                .filter(e -> tenant.equals(e.getTenantId()))
                .filter(e -> e.getEmploymentStatus() == Employee.EmploymentStatus.ACTIVE
                        || e.getEmploymentStatus() == Employee.EmploymentStatus.ON_LEAVE)
                .filter(e -> e.getHireDate() == null || !e.getHireDate().isAfter(date))
                .filter(e -> scope == null || scope.equals(e.getBranchId())).toList();
        Map<Long, Employee> byId = selected.stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        // Three days cover every still-valid scheduled/overnight session under AttendanceSessionPolicy.
        var schedules = scheduleResolver.forReport(tenant, selected, date.minusDays(3), date);
        List<AttendanceLog> recent = logs.findByTenantIdAndCheckInTimeBetween(tenant, now.minusDays(3), now).stream()
                .filter(l -> tenant.equals(l.getTenantId()) && byId.containsKey(l.getEmployeeId())).toList();
        Map<Long, Transition> latest = new HashMap<>();
        Set<Long> attendedIds = new HashSet<>();
        for (AttendanceLog log : recent) {
            LocalDateTime entry = log.getCheckInTime();
            if (entry == null || entry.isAfter(now)) continue;
            LocalDateTime lastEntry = Boolean.TRUE.equals(log.getManualOverride()) || log.getLastEntryTime() == null
                    || log.getLastEntryTime().isAfter(now) ? entry : log.getLastEntryTime();
            record(latest, log.getEmployeeId(), new Transition(log, lastEntry, true));
            LocalDateTime exit = log.getCheckOutTime();
            if (exit != null && !exit.isAfter(now)) record(latest, log.getEmployeeId(), new Transition(log, exit, false));
            boolean overlapsToday = !entry.isBefore(date.atStartOfDay())
                    || exit != null && exit.isAfter(date.atStartOfDay());
            if (overlapsToday) attendedIds.add(log.getEmployeeId());
        }

        Map<Long, Long> counts = new HashMap<>();
        long unknown = 0, unresolved = 0;
        for (var item : latest.entrySet()) {
            Transition last = item.getValue();
            if (!last.entry()) continue;
            var schedule = schedules.apply(byId.get(item.getKey()), last.log().getCheckInTime().toLocalDate());
            LocalDateTime calendarDeadline = AttendanceSessionPolicy.calendarDayDeadline(last.log());
            boolean expired = calendarDeadline != null ? !now.isBefore(calendarDeadline)
                    : ShiftTypes.isFlexible(last.log().getShiftType())
                    ? !now.isBefore(last.log().getCheckInTime().plusHours(AttendanceSessionPolicy.FLEXIBLE_MAX_SESSION_HOURS))
                    : AttendanceSessionPolicy.isExpired(last.log().getCheckInTime(), schedule, now);
            if (expired) { unresolved++; continue; }
            attendedIds.add(item.getKey());
            Long area = last.log().getAreaSnapshotId();
            if (area == null) unknown++;
            else counts.merge(area, 1L, Long::sum);
        }

        long absent = 0, late = 0, early = 0, permitted = 0, leave = 0;
        for (Employee employee : selected) {
            var days = attendance.getEmployeeAttendanceForReport(employee, date, date, schedules);
            if (days.isEmpty()) continue;
            var day = days.get(0);
            if (day.getCheckInTime() != null && !day.getCheckInTime().toLocalDateTime().isAfter(now)) attendedIds.add(employee.getId());
            var schedule = schedules.apply(employee, date);
            boolean started = schedule.startTime() == null || !now.toLocalTime().isBefore(schedule.startTime());
            if (day.getStatus() == AttendanceStatus.ABSENT && started && !attendedIds.contains(employee.getId())) absent++;
            if (day.getLateMinutes() != null && day.getLateMinutes() > 0) late++;
            if (day.getEarlyLeaveMinutes() != null && day.getEarlyLeaveMinutes() > 0) early++;
            if (Boolean.TRUE.equals(day.getHasPermission())) permitted++;
            if (day.getStatus() == AttendanceStatus.ON_LEAVE) leave++;
        }
        List<Branch> visibleAreas = branches.findByTenantId(tenant).stream()
                .filter(b -> tenant.equals(b.getTenantId()))
                .filter(b -> scope == null || scope.equals(b.getId()))
                .sorted(Comparator.comparing(Branch::getName)).toList();
        Set<Long> knownAreaIds = visibleAreas.stream().map(Branch::getId).collect(Collectors.toSet());
        // Deleted or inaccessible areas must not be silently attributed to the employee's home area.
        unknown += counts.entrySet().stream().filter(e -> !knownAreaIds.contains(e.getKey())).mapToLong(Map.Entry::getValue).sum();
        var areaRows = visibleAreas.stream().map(b -> new DashboardAttendanceDTO.AreaOccupancy(
                b.getId(), b.getName(), counts.getOrDefault(b.getId(), 0L))).toList();
        return new DashboardAttendanceDTO(date, AppTimeZone.toOffsetDateTime(now), attendedIds.size(), absent,
                late, early, permitted, leave, areaRows, unknown, unresolved);
    }

    private static void record(Map<Long, Transition> latest, Long employee, Transition next) {
        latest.merge(employee, next, (previous, candidate) -> {
            int order = candidate.time().compareTo(previous.time());
            if (order > 0 || order == 0 && !candidate.entry()) return candidate;
            if (order == 0 && candidate.entry() == previous.entry()
                    && candidate.log().getId() > previous.log().getId()) return candidate;
            return previous;
        });
    }

    private record Transition(AttendanceLog log, LocalDateTime time, boolean entry) {}
}
