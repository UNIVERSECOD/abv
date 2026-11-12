package com.hic.service;

import com.hic.model.AttendanceLog;
import com.hic.model.AttendanceRecord;
import com.hic.model.Employee;
import com.hic.exception.BadRequestException;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.AttendanceRecordRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.EmployeePermissionRepository;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendanceCalculationService {
    private final AttendanceLogRepository attendanceLogRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeePermissionRepository employeePermissionRepository;
    private final HolidayService holidayService;
    private final LeaveService leaveService;
    private final AttendanceInferenceService attendanceInferenceService;
    private final AttendanceScheduleResolver attendanceScheduleResolver;
    private final AttendanceTimeCalculator attendanceTimeCalculator;
    private final AttendancePermissionCalculator attendancePermissionCalculator;
    private final AttendanceService attendanceService;

    @Transactional
    public AttendanceRecord calculateForDay(Long employeeId, LocalDate workDate) {
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        AttendanceRecord record = attendanceRecordRepository.findByEmployeeIdAndWorkDate(employeeId, workDate)
                .orElseGet(AttendanceRecord::new);

        record.setEmployeeId(employeeId);
        record.setWorkDate(workDate);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null && employee != null) {
            tenantId = employee.getTenantId();
        }
        if (tenantId == null) {
            tenantId = record.getTenantId();
        }
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required for attendance calculation");
        }
        record.setTenantId(tenantId);

        AttendanceScheduleResolver.DaySchedule schedule = attendanceScheduleResolver.resolve(employee, workDate);
        record.setShiftType(schedule.shiftType());
        record.setTimetableId(schedule.timetableId());

        List<AttendanceLog> logs = findDayLogs(employeeId, workDate);
        AttendanceInferenceService.AttendanceInference inference = attendanceInferenceService.inferDay(logs, workDate);
        LocalDateTime firstEntry = inference.firstEntry();
        LocalDateTime lastExit = inference.lastExit();
        record.setEntryTime(firstEntry);
        record.setExitTime(lastExit);

        AttendanceTimeCalculator.Calculation calculation = attendanceTimeCalculator.calculate(
                workDate, inference, schedule);
        var permissions = new java.util.ArrayList<>(employeePermissionRepository.findByTenantIdAndEmployeeIdAndDateRange(
                tenantId, employeeId, workDate, workDate));
        if (schedule.flexible()) permissions.addAll(employeePermissionRepository.findByTenantIdAndEmployeeIdAndDateRange(
                tenantId, employeeId, workDate.minusDays(1), workDate.minusDays(1)));
        AttendancePermissionCalculator.Result permissionResult = attendancePermissionCalculator.applyAllocated(
                workDate,
                inference,
                schedule,
                calculation,
                permissions.stream().distinct().toList(),
                date -> attendanceScheduleResolver.resolve(employee, date)
        );
        record.setWorkedMinutes(permissionResult.workedMinutes());
        record.setOvertimeMinutes(permissionResult.overtimeMinutes());
        record.setLateMinutes(permissionResult.lateMinutes());
        record.setEarlyLeaveMinutes(permissionResult.earlyLeaveMinutes());
        record.setPermissionMinutes(permissionResult.permissionMinutes());
        record.setCreditedPermissionMinutes(permissionResult.creditedPermissionMinutes());

        if (leaveService.hasActiveLeave(employeeId, workDate)) {
            record.setStatus("ON_LEAVE");
        } else if (holidayService.isHoliday(workDate)) {
            record.setStatus("HOLIDAY");
        } else {
            record.setStatus(permissionResult.status().name());
        }

        return attendanceRecordRepository.save(record);
    }

    public LocalDateTime getFirstEntry(Long employeeId, LocalDate workDate) {
        return attendanceInferenceService.inferDay(findDayLogs(employeeId, workDate), workDate).firstEntry();
    }

    public LocalDateTime getLastExit(Long employeeId, LocalDate workDate) {
        return attendanceInferenceService.inferDay(findDayLogs(employeeId, workDate), workDate).lastExit();
    }

    @Transactional
    public void recalculate(LocalDate start, LocalDate end, Long employeeId) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new BadRequestException("Başlanğıc tarixi bitmə tarixindən sonra ola bilməz.");
        }
        List<Employee> employees;
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required to recalculate attendance");
        }
        if (employeeId != null) {
            employees = employeeRepository.findById(employeeId)
                    .filter(employee -> tenantId.equals(employee.getTenantId()))
                    .map(List::of).orElse(List.of());
        } else {
            employees = employeeRepository.findByTenantId(tenantId, org.springframework.data.domain.Pageable.unpaged())
                    .getContent().stream()
                    .filter(employee -> !Employee.EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus()))
                    .toList();
        }

        for (Employee employee : employees) {
            LocalDate date = start;
            while (!date.isAfter(end)) {
                calculateForDay(employee.getId(), date);
                attendanceService.generateDailySummary(employee.getId(), date);
                date = date.plusDays(1);
            }
        }
    }

    private List<AttendanceLog> findDayLogs(Long employeeId, LocalDate workDate) {
        List<AttendanceLog> candidates = attendanceLogRepository.findByEmployeeIdAndCheckInTimeBetween(
                employeeId,
                workDate.atStartOfDay(),
                workDate.plusDays(1).atStartOfDay().minusNanos(1)
        );
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        Long tenant = TenantContext.getTenantId() != null ? TenantContext.getTenantId()
                : employee != null ? employee.getTenantId() : null;
        var all = new java.util.ArrayList<>(candidates);
        all.addAll(attendanceLogRepository.findManagedLateEntries(tenant, employeeId, workDate, workDate,
                workDate.plusDays(1).atStartOfDay()));
        all.addAll(attendanceLogRepository.findClosedCarryIn(tenant, employeeId, workDate.atStartOfDay()));
        return AttendanceDayAllocation.forDay(all, workDate,
                date -> attendanceScheduleResolver.resolve(employee, date).shiftType());
    }
}
