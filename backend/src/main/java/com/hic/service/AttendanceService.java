package com.hic.service;

import com.hic.dto.AttendanceDTO;
import com.hic.dto.EmployeeAttendanceRowDTO;
import com.hic.dto.EmployeeAttendanceSummaryDTO;
import com.hic.dto.AttendanceLogDTO;
import com.hic.dto.AttendanceSessionDTO;
import com.hic.dto.DailyAttendanceSummaryDTO;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.AttendanceLog;
import com.hic.model.DailyAttendanceSummary;
import com.hic.model.DailyAttendanceSummary.AttendanceStatus;
import com.hic.model.Employee;
import com.hic.model.EmployeePermission;
import com.hic.model.LeaveRequest;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.DailyAttendanceSummaryRepository;
import com.hic.repository.EmployeePermissionRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.util.AppTimeZone;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceLogRepository attendanceLogRepository;
    private final DailyAttendanceSummaryRepository summaryRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeePermissionRepository employeePermissionRepository;
    private final UserScopeService userScopeService;
    private final AttendanceInferenceService attendanceInferenceService;
    private final AttendanceScheduleResolver attendanceScheduleResolver;
    private final AttendanceTimeCalculator attendanceTimeCalculator;
    private final AttendancePermissionCalculator attendancePermissionCalculator;

    @Transactional
    public AttendanceLogDTO logAttendance(AttendanceDTO dto) {
        requireEmployeeInCurrentTenant(dto.getEmployeeId());
        AttendanceLog log = new AttendanceLog();
        log.setEmployeeId(dto.getEmployeeId());
        log.setCheckInTime(dto.getCheckInTime());
        log.setCheckOutTime(dto.getCheckOutTime());
        log.setDeviceId(dto.getDeviceId());
        log.setDoorId(dto.getDoorId());
        log.setEventType(dto.getEventType());
        log.setVerificationMethod(dto.getVerificationMethod());
        log.setStatus("ACTIVE");
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            log.setTenantId(tenantId);
        }
        AttendanceLog saved = attendanceLogRepository.save(log);
        if (dto.getCheckInTime() != null) {
            Employee employee = employeeRepository.findById(dto.getEmployeeId()).orElse(null);
            String shift = attendanceScheduleResolver.resolve(employee, dto.getCheckInTime().toLocalDate()).shiftType();
            for (var date : AttendanceDayAllocation.affectedDates(dto.getCheckInTime(), dto.getCheckOutTime(), shift)) {
                generateDailySummary(dto.getEmployeeId(), date);
            }
        }
        return toLogDTO(saved);
    }

    public List<AttendanceLogDTO> getLogsForEmployee(Long employeeId, LocalDateTime start, LocalDateTime end) {
        Long tenantId = TenantContext.getTenantId();
        List<AttendanceLog> logs = tenantId != null
                ? attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTimeBetween(tenantId, employeeId, start, end)
                : attendanceLogRepository.findByEmployeeIdAndCheckInTimeBetween(employeeId, start, end);
        return logs.stream().map(this::toLogDTO).collect(Collectors.toList());
    }

    public List<AttendanceLogDTO> getLogsByDateRange(LocalDateTime start, LocalDateTime end) {
        Long tenantId = TenantContext.getTenantId();
        List<AttendanceLog> logs = tenantId != null
                ? attendanceLogRepository.findByTenantIdAndCheckInTimeBetween(tenantId, start, end)
                : attendanceLogRepository.findByCheckInTimeBetween(start, end);
        return logs.stream().map(this::toLogDTO).collect(Collectors.toList());
    }

    @Transactional
    public DailyAttendanceSummaryDTO generateDailySummary(Long employeeId, LocalDate date) {
        requireEmployeeInCurrentTenant(employeeId);
        List<AttendanceLog> logs = findDayLogs(employeeId, date);
        AttendanceInferenceService.AttendanceInference inference = attendanceInferenceService.inferDay(logs, date);
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        AttendanceScheduleResolver.DaySchedule daySchedule = attendanceScheduleResolver.resolve(employee, date);
        AttendanceTimeCalculator.Calculation calculation = attendanceTimeCalculator.calculate(
                date, inference, daySchedule);
        List<EmployeePermission> permissions = findPermissions(employeeId, date, date);
        permissions = new ArrayList<>(permissions);
        if (daySchedule.flexible()) permissions.addAll(findPermissions(employeeId, date.minusDays(1), date.minusDays(1)));
        AttendancePermissionCalculator.Result permissionResult = attendancePermissionCalculator.applyAllocated(
                date, inference, daySchedule, calculation, permissions.stream().distinct().toList(),
                sourceDate -> attendanceScheduleResolver.resolve(employee, sourceDate));

        Optional<DailyAttendanceSummary> existing = summaryRepository.findByEmployeeIdAndAttendanceDate(employeeId, date);
        DailyAttendanceSummary summary = existing.orElse(new DailyAttendanceSummary());
        summary.setTenantId(resolveAttendanceTenantId(employeeId));
        summary.setEmployeeId(employeeId);
        summary.setAttendanceDate(date);
        summary.setIsHoliday(false);
        summary.setIsLeave(permissionResult.hasPermission());
        summary.setIsStandardDay(daySchedule.workingDay());
        boolean workedOnDayOff = !daySchedule.workingDay() && inference.firstEntry() != null;
        summary.setIsAdditionalDay(workedOnDayOff);
        summary.setIsExtraDay(workedOnDayOff);

        summary.setCheckInTime(inference.firstEntry());
        summary.setCheckOutTime(inference.lastExit());
        summary.setHoursWorked(permissionResult.workedMinutes() / 60.0);
        summary.setLateMinutes(permissionResult.lateMinutes());
        summary.setEarlyLeaveMinutes(permissionResult.earlyLeaveMinutes());
        summary.setPermissionMinutes(permissionResult.permissionMinutes());
        summary.setCreditedPermissionMinutes(permissionResult.creditedPermissionMinutes());
        summary.setAttendanceStatus(permissionResult.status());

        return toSummaryDTO(summaryRepository.save(summary));
    }

    public List<DailyAttendanceSummaryDTO> getDailySummary(Long employeeId, LocalDate start, LocalDate end) {
        return summaryRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, start, end)
                .stream().map(this::toSummaryDTO).collect(Collectors.toList());
    }

    public List<EmployeeAttendanceRowDTO> getEmployeeAttendance(Long employeeId, LocalDate start, LocalDate end) {
        Employee employee = getAccessibleEmployee(employeeId);
        return getEmployeeAttendanceForReport(employee, start, end, attendanceScheduleResolver::resolve);
    }

    List<EmployeeAttendanceRowDTO> getEmployeeAttendanceForReport(Employee employee, LocalDate start, LocalDate end,
            java.util.function.BiFunction<Employee, LocalDate, AttendanceScheduleResolver.DaySchedule> schedules) {
        Long employeeId = employee.getId();
        Long tenantId = TenantContext.getTenantId();

        // Include closed carry-in sessions so flexible hours survive day/month range boundaries.
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.atTime(LocalTime.MAX);
        List<AttendanceLog> logs = tenantId != null
                ? attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTimeBetween(tenantId, employeeId, rangeStart, rangeEnd)
                : attendanceLogRepository.findByEmployeeIdAndCheckInTimeBetween(employeeId, rangeStart, rangeEnd);
        logs = new ArrayList<>(logs);
        logs.addAll(attendanceLogRepository.findManagedLateEntries(tenantId != null ? tenantId : employee.getTenantId(),
                employeeId, start, end, end.plusDays(1).atStartOfDay()));
        logs.addAll(attendanceLogRepository.findClosedCarryIn(
                tenantId != null ? tenantId : employee.getTenantId(), employeeId, rangeStart));

        Map<LocalDate, DailyAttendanceSummary> summariesByDate = summaryRepository
                .findByEmployeeIdAndAttendanceDateBetween(employeeId, start, end)
                .stream()
                .collect(Collectors.toMap(DailyAttendanceSummary::getAttendanceDate, summary -> summary, (left, right) -> right, LinkedHashMap::new));

        List<LeaveRequest> approvedLeaves = tenantId != null
                ? leaveRequestRepository.findApprovedByTenantAndEmployeeIdAndDateRange(tenantId, employeeId, start, end)
                : leaveRequestRepository.findApprovedByEmployeeIdAndDateRange(employeeId, start, end);
        List<EmployeePermission> approvedPermissions = tenantId != null
                ? employeePermissionRepository.findByTenantIdAndEmployeeIdAndDateRange(tenantId, employeeId, start, end)
                : employeePermissionRepository.findByEmployeeIdAndDateRange(employeeId, start, end);
        approvedPermissions = new ArrayList<>(approvedPermissions);
        approvedPermissions.addAll(findPermissions(employeeId, start.minusDays(1), start.minusDays(1)));
        List<EmployeeAttendanceRowDTO> rows = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            DailyAttendanceSummary summary = summariesByDate.get(currentDate);
            List<AttendanceLog> dayLogs = AttendanceDayAllocation.forDay(logs, currentDate,
                    sourceDate -> schedules.apply(employee, sourceDate).shiftType());
            boolean onLeave = overlapsLeave(approvedLeaves, currentDate);
            AttendanceInferenceService.AttendanceInference inference = attendanceInferenceService.inferDay(dayLogs, currentDate);

            // Daily totals use clipped flexible portions; source/detail sessions remain whole.
            LocalDateTime firstCheckIn = inference.firstEntry();
            LocalDateTime lastCheckOut = inference.lastExit();
            AttendanceScheduleResolver.DaySchedule daySchedule = schedules.apply(employee, currentDate);
            AttendanceTimeCalculator.Calculation calculation = attendanceTimeCalculator.calculate(
                    currentDate, inference, daySchedule);
            AttendancePermissionCalculator.Result permissionResult = attendancePermissionCalculator.applyAllocated(
                    currentDate, inference, daySchedule, calculation, approvedPermissions.stream().distinct().toList(),
                    sourceDate -> schedules.apply(employee, sourceDate));
            double hoursWorked = permissionResult.workedMinutes() / 60.0;
            int lateMinutes = permissionResult.lateMinutes();
            int earlyLeaveMinutes = permissionResult.earlyLeaveMinutes();
            int permissionMinutes = permissionResult.permissionMinutes();
            int creditedPermissionMinutes = permissionResult.creditedPermissionMinutes();
            AttendanceStatus attendanceStatus = permissionResult.status();

            if (summary != null && summary.getHoursWorked() != null && dayLogs.isEmpty()) {
                hoursWorked = summary.getHoursWorked();
                firstCheckIn = summary.getCheckInTime();
                lastCheckOut = summary.getCheckOutTime();
                lateMinutes = summary.getLateMinutes() != null ? summary.getLateMinutes() : 0;
                earlyLeaveMinutes = summary.getEarlyLeaveMinutes() != null ? summary.getEarlyLeaveMinutes() : 0;
                permissionMinutes = summary.getPermissionMinutes() != null ? summary.getPermissionMinutes() : 0;
                creditedPermissionMinutes = summary.getCreditedPermissionMinutes() != null
                        ? summary.getCreditedPermissionMinutes()
                        : 0;
                if (summary.getAttendanceStatus() != null) {
                    attendanceStatus = summary.getAttendanceStatus();
                }
            }

            EmployeeAttendanceRowDTO row = new EmployeeAttendanceRowDTO();
            row.setDate(currentDate);
            row.setCheckInTime(toOffsetDateTime(firstCheckIn));
            row.setCheckOutTime(toOffsetDateTime(lastCheckOut));
            row.setEntryMethod(endpointMethod(logs, firstCheckIn, true));
            row.setExitMethod(endpointMethod(logs, lastCheckOut, false));
            row.setManagedAttendance(dayLogs.stream().anyMatch(log -> log.getAnprWorkDate() != null));
            row.setHoursWorked(hoursWorked);
            row.setLateMinutes(lateMinutes);
            row.setEarlyLeaveMinutes(earlyLeaveMinutes);
            row.setPermissionMinutes(permissionMinutes);
            row.setCreditedPermissionMinutes(creditedPermissionMinutes);
            row.setHasPermission(permissionResult.hasPermission());
            row.setStatus(onLeave ? AttendanceStatus.ON_LEAVE : attendanceStatus);
            row.setNotes(buildNotes(approvedLeaves, approvedPermissions, currentDate));
            row.setShiftType(daySchedule.shiftType());
            row.setSessions(toSessionDtos(attendanceInferenceService.inferDay(logs, currentDate).segments()));
            rows.add(row);
        }

        return rows;
    }

    private static String endpointMethod(List<AttendanceLog> logs, LocalDateTime time, boolean entry) {
        if (time == null) return null;
        return logs.stream()
                .filter(log -> time.equals(entry ? log.getCheckInTime() : log.getCheckOutTime()))
                .map(log -> entry ? log.getEntryMethod() : log.getExitMethod())
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    public EmployeeAttendanceSummaryDTO getEmployeeAttendanceSummary(Long employeeId, LocalDate start, LocalDate end) {
        List<EmployeeAttendanceRowDTO> rows = getEmployeeAttendance(employeeId, start, end);
        EmployeeAttendanceSummaryDTO summary = new EmployeeAttendanceSummaryDTO();
        summary.setTotalDays(rows.size());
        summary.setWorkingDays(rows.stream()
                .filter(row -> row.getStatus() == AttendanceStatus.PRESENT
                        || row.getStatus() == AttendanceStatus.LATE
                        || row.getStatus() == AttendanceStatus.EARLY_LEAVE
                        || row.getStatus() == AttendanceStatus.WORKDAY_COMPLETE
                        || row.getStatus() == AttendanceStatus.PERMITTED_EARLY_LEAVE
                        || (row.getStatus() == AttendanceStatus.ON_PERMISSION
                        && row.getHoursWorked() != null
                        && row.getHoursWorked() > 0))
                .count());
        summary.setTotalHours(rows.stream()
                .map(EmployeeAttendanceRowDTO::getHoursWorked)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum());
        summary.setAbsentDays(rows.stream().filter(row -> row.getStatus() == AttendanceStatus.ABSENT).count());
        summary.setLateDays(rows.stream().filter(row -> row.getLateMinutes() != null && row.getLateMinutes() > 0).count());
        summary.setLeaveDays(rows.stream().filter(row ->
                row.getStatus() == AttendanceStatus.ON_LEAVE
                        || row.getStatus() == AttendanceStatus.ON_PERMISSION
                        || row.getStatus() == AttendanceStatus.PERMITTED_EARLY_LEAVE).count());
        return summary;
    }

    private AttendanceLogDTO toLogDTO(AttendanceLog log) {
        AttendanceLogDTO dto = new AttendanceLogDTO();
        dto.setId(log.getId());
        dto.setEmployeeId(log.getEmployeeId());
        dto.setCheckInTime(toOffsetDateTime(log.getCheckInTime()));
        dto.setCheckOutTime(toOffsetDateTime(log.getCheckOutTime()));
        dto.setDeviceId(log.getDeviceId());
        dto.setDoorId(log.getDoorId());
        dto.setEventType(log.getEventType());
        dto.setVerificationMethod(log.getVerificationMethod());
        dto.setStatus(log.getStatus());
        dto.setCreatedAt(toOffsetDateTime(log.getCreatedAt()));
        return dto;
    }

    private DailyAttendanceSummaryDTO toSummaryDTO(DailyAttendanceSummary s) {
        DailyAttendanceSummaryDTO dto = new DailyAttendanceSummaryDTO();
        dto.setId(s.getId());
        dto.setEmployeeId(s.getEmployeeId());
        dto.setAttendanceDate(s.getAttendanceDate());
        dto.setCheckInTime(toOffsetDateTime(s.getCheckInTime()));
        dto.setCheckOutTime(toOffsetDateTime(s.getCheckOutTime()));
        dto.setHoursWorked(s.getHoursWorked());
        dto.setLateMinutes(s.getLateMinutes());
        dto.setEarlyLeaveMinutes(s.getEarlyLeaveMinutes());
        dto.setPermissionMinutes(s.getPermissionMinutes());
        dto.setCreditedPermissionMinutes(s.getCreditedPermissionMinutes());
        dto.setIsStandardDay(s.getIsStandardDay());
        dto.setIsAdditionalDay(s.getIsAdditionalDay());
        dto.setIsExtraDay(s.getIsExtraDay());
        dto.setIsHoliday(s.getIsHoliday());
        dto.setIsLeave(s.getIsLeave());
        dto.setAttendanceStatus(s.getAttendanceStatus());
        return dto;
    }

    private void requireEmployeeInCurrentTenant(Long employeeId) {
        Long tenantId = TenantContext.getTenantId();
        // Background calculations have no request tenant; HR writes must stay inside their company.
        if (tenantId != null && employeeRepository.findByTenantIdAndId(tenantId, employeeId).isEmpty()) {
            throw new ResourceNotFoundException("Employee", employeeId);
        }
    }

    private Employee getAccessibleEmployee(Long employeeId) {
        Long tenantId = TenantContext.getTenantId();
        Employee employee = tenantId != null
                ? employeeRepository.findByTenantIdAndId(tenantId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId))
                : employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));

        Long branchScope = userScopeService.resolveBranchScope(null);
        if (branchScope != null && (employee.getBranchId() == null || !branchScope.equals(employee.getBranchId()))) {
            throw new ResourceNotFoundException("Employee", employeeId);
        }
        return employee;
    }

    private Long resolveAttendanceTenantId(Long employeeId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            return tenantId;
        }
        return employeeRepository.findById(employeeId)
                .map(Employee::getTenantId)
                .orElse(null);
    }

    private boolean overlapsLeave(List<LeaveRequest> leaves, LocalDate date) {
        return leaves.stream().anyMatch(leave ->
                !leave.getStartDate().isAfter(date) && !leave.getEndDate().isBefore(date));
    }

    private String buildNotes(List<LeaveRequest> leaves, List<EmployeePermission> permissions, LocalDate date) {
        List<EmployeePermission> dayPermissions = permissions.stream()
                .filter(item -> !item.getStartDate().isAfter(date) && !item.getEndDate().isBefore(date))
                .toList();
        if (!dayPermissions.isEmpty()) {
            return dayPermissions.stream()
                    .map(this::permissionNote)
                    .collect(Collectors.joining("; "));
        }

        boolean onLeave = leaves.stream()
                .anyMatch(item -> !item.getStartDate().isAfter(date) && !item.getEndDate().isBefore(date));
        return onLeave ? "Approved leave" : null;
    }

    private String permissionNote(EmployeePermission permission) {
        String period = permission.isFullDay()
                ? "Tam gün icazə"
                : "İcazə " + permission.getStartTime() + "–" + permission.getEndTime();
        String reason = permission.getReason() == null || permission.getReason().isBlank()
                ? ""
                : " · " + permission.getReason();
        String workTimeEffect = Boolean.TRUE.equals(permission.getDeductFromWorkHours())
                ? " · İş vaxtından çıxılır"
                : " · İş vaxtına daxildir";
        return period + reason + workTimeEffect;
    }

    private List<EmployeePermission> findPermissions(Long employeeId, LocalDate start, LocalDate end) {
        Long tenantId = TenantContext.getTenantId();
        return tenantId != null
                ? employeePermissionRepository.findByTenantIdAndEmployeeIdAndDateRange(tenantId, employeeId, start, end)
                : employeePermissionRepository.findByEmployeeIdAndDateRange(employeeId, start, end);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        return AppTimeZone.toOffsetDateTime(localDateTime);
    }

    private List<AttendanceLog> findDayLogs(Long employeeId, LocalDate date) {
        List<AttendanceLog> candidates = attendanceLogRepository.findByEmployeeIdAndCheckInTimeBetween(
                employeeId,
                date.atStartOfDay(),
                date.plusDays(1).atStartOfDay().minusNanos(1)
        );
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        Long tenant = TenantContext.getTenantId() != null ? TenantContext.getTenantId()
                : employee != null ? employee.getTenantId() : null;
        var all = new ArrayList<>(candidates);
        all.addAll(attendanceLogRepository.findManagedLateEntries(tenant, employeeId, date, date,
                date.plusDays(1).atStartOfDay()));
        all.addAll(attendanceLogRepository.findClosedCarryIn(tenant, employeeId, date.atStartOfDay()));
        return AttendanceDayAllocation.forDay(all, date,
                sourceDate -> attendanceScheduleResolver.resolve(employee, sourceDate).shiftType());
    }

    private List<AttendanceSessionDTO> toSessionDtos(List<AttendanceInferenceService.SessionSegment> segments) {
        return segments.stream()
                .map(segment -> {
                    AttendanceSessionDTO session = new AttendanceSessionDTO();
                    session.setCheckInTime(toOffsetDateTime(segment.checkInTime()));
                    session.setCheckOutTime(toOffsetDateTime(segment.checkOutTime()));
                    return session;
                })
                .collect(Collectors.toList());
    }

}
