package com.hic.service;

import com.hic.dto.TabelMonthlyDTO.McgDayDTO;
import com.hic.model.*;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.EmployeePermissionRepository;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class McgTabelProjectionService {
    private final AttendanceLogRepository logs;
    private final EmployeePermissionRepository permissions;
    private final AttendanceScheduleResolver schedules;
    private final McgTabelCalculator calculator;

    @FunctionalInterface
    public interface DayProjector {
        McgDayDTO project(Employee employee, LocalDate date, DailyAttendanceSummary summary);
    }

    public DayProjector forMonth(Long tenantId, List<Employee> employees, LocalDate start, LocalDate end) {
        if (employees.isEmpty()) return (employee, date, summary) -> {
            throw new IllegalArgumentException("No employees selected for projection");
        };
        var source = new ArrayList<>(logs.findByTenantIdAndCheckInTimeBetween(
                tenantId, start.atStartOfDay(), end.atTime(LocalTime.MAX)));
        source.addAll(logs.findManagedLateEntries(tenantId, null, start, end, end.plusDays(1).atStartOfDay()));
        source.addAll(logs.findClosedCarryIn(tenantId, null, start.atStartOfDay()));
        var ids = employees.stream().map(Employee::getId).collect(Collectors.toSet());
        var byEmployee = source.stream().filter(log -> ids.contains(log.getEmployeeId()))
                .collect(Collectors.groupingBy(AttendanceLog::getEmployeeId));
        var permissionMap = permissions.findByDateRange(tenantId, start.minusDays(1), end).stream()
                .filter(p -> ids.contains(p.getEmployeeId())).collect(Collectors.groupingBy(EmployeePermission::getEmployeeId));
        var earliest = byEmployee.values().stream().flatMap(List::stream)
                .map(AttendanceLog::getCheckInTime).filter(Objects::nonNull)
                .map(java.time.LocalDateTime::toLocalDate).min(LocalDate::compareTo).orElse(start);
        var scheduleFor = schedules.forReport(tenantId, employees,
                earliest.isBefore(start.minusDays(1)) ? earliest : start.minusDays(1), end);
        return (employee, date, summary) -> {
            var schedule = scheduleFor.apply(employee, date);
            int expectedMinutes = schedule.assigned() ? schedule.expectedMinutes() : 0;
            boolean scheduledDayOff = schedule.assigned() && !schedule.workingDay();
            if (date.isAfter(AppTimeZone.today())) {
                return new McgDayDTO(false, 0, 0, 0, 0, 0, "FUTURE", true, expectedMinutes, scheduledDayOff);
            }
            var dayLogs = AttendanceDayAllocation.forDay(byEmployee.getOrDefault(employee.getId(), List.of()), date,
                    entryDate -> scheduleFor.apply(employee, entryDate).shiftType());
            var result = calculator.calculate(date, dayLogs, schedule,
                    permissionMap.getOrDefault(employee.getId(), List.of()), d -> scheduleFor.apply(employee, d));
            if (dayLogs.isEmpty() && !result.fullDay() && summary != null
                    && summary.getHoursWorked() != null && summary.getHoursWorked() > 0) {
                // Do not invent a complete day from historical aggregates without source evidence.
                int minutes = (int) Math.round(summary.getHoursWorked() * 60);
                return new McgDayDTO(false, minutes, minutes, 0, 0, 0, "SUMMARY_ONLY", false,
                        expectedMinutes, scheduledDayOff);
            }
            return result;
        };
    }
}
