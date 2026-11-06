package com.hic.service;

import com.hic.dto.AttendanceCorrectionRequest;
import com.hic.dto.AttendanceLogDTO;
import com.hic.dto.OpenAttendanceSessionDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.exception.ResourceNotFoundException;
import com.hic.exception.BadRequestException;
import com.hic.model.AttendanceLog;
import com.hic.model.AttendanceLogAdjustment;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogAdjustmentRepository;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.AppTimeZone;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceSessionManagementService {

    private final AttendanceLogRepository attendanceLogRepository;
    private final AttendanceLogAdjustmentRepository adjustmentRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceCalculationService calculationService;
    private final AttendanceService attendanceService;
    private final AttendanceSessionPolicy sessionPolicy;
    private final UserScopeService userScopeService;
    private final AttendanceScheduleResolver scheduleResolver;

    @Transactional
    public PaginatedResponse<OpenAttendanceSessionDTO> getOpenSessions(int page, int size) {
        Long tenantId = requireTenantId();
        Long branchScope = userScopeService.resolveBranchScope(null);
        var sessionPage = attendanceLogRepository.findOpenSessions(
                tenantId, branchScope, PageRequest.of(page, size));
        List<AttendanceLog> sessions = sessionPage.getContent();
        Set<Long> employeeIds = sessions.stream()
                .map(AttendanceLog::getEmployeeId)
                .collect(Collectors.toSet());
        Map<Long, Employee> employees = employeeRepository.findAllById(employeeIds).stream()
                .filter(employee -> tenantId.equals(employee.getTenantId()))
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<OpenAttendanceSessionDTO> content = sessions.stream()
                .filter(session -> employees.containsKey(session.getEmployeeId()))
                .map(session -> toOpenSession(session, employees.get(session.getEmployeeId())))
                .toList();
        return PaginatedResponse.of(content, sessionPage.getTotalElements(),
                sessionPage.getTotalPages(), sessionPage.getNumber(), sessionPage.getSize());
    }

    @Transactional
    public AttendanceLogDTO correctSession(Long attendanceLogId, AttendanceCorrectionRequest request) {
        Long tenantId = requireTenantId();
        AttendanceLog session = attendanceLogRepository.findByTenantIdAndId(tenantId, attendanceLogId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance session", attendanceLogId));
        Employee employee = employeeRepository.findByTenantIdAndId(tenantId, session.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee", session.getEmployeeId()));
        Long branchScope = userScopeService.resolveBranchScope(null);
        if (!isInBranchScope(employee, branchScope)) {
            throw new ResourceNotFoundException("Attendance session", attendanceLogId);
        }
        if (!request.getCheckOutTime().isAfter(request.getCheckInTime())) {
            throw new BadRequestException("Check-out time must be after check-in time");
        }

        Set<LocalDate> affectedDates = new LinkedHashSet<>();
        affectedDates.addAll(AttendanceDayAllocation.affectedDates(
                session.getCheckInTime(), session.getCheckOutTime(), shiftFor(session, employee, session.getCheckInTime())));
        affectedDates.addAll(AttendanceDayAllocation.affectedDates(
                request.getCheckInTime(), request.getCheckOutTime(), shiftFor(session, employee, request.getCheckInTime())));

        AttendanceLogAdjustment adjustment = new AttendanceLogAdjustment();
        adjustment.setTenantId(tenantId);
        adjustment.setAttendanceLogId(session.getId());
        adjustment.setPreviousCheckInTime(session.getCheckInTime());
        adjustment.setPreviousCheckOutTime(session.getCheckOutTime());
        adjustment.setNewCheckInTime(request.getCheckInTime());
        adjustment.setNewCheckOutTime(request.getCheckOutTime());
        adjustment.setReason(request.getReason().trim());
        adjustment.setCorrectedBy(currentUsername());
        adjustmentRepository.save(adjustment);

        session.setCheckInTime(request.getCheckInTime());
        session.setCheckOutTime(request.getCheckOutTime());
        session.setManualOverride(true);
        session.setStatus("MANUALLY_CORRECTED");
        AttendanceLog saved = attendanceLogRepository.save(session);

        for (LocalDate date : affectedDates) {
            calculationService.calculateForDay(employee.getId(), date);
            attendanceService.generateDailySummary(employee.getId(), date);
        }
        return toLogDTO(saved);
    }

    private OpenAttendanceSessionDTO toOpenSession(AttendanceLog session, Employee employee) {
        String effectiveStatus = sessionPolicy.effectiveStatus(employee, session);
        if (!Boolean.TRUE.equals(session.getManualOverride())
                && !effectiveStatus.equalsIgnoreCase(session.getStatus() == null ? "" : session.getStatus())) {
            session.setStatus(effectiveStatus);
            attendanceLogRepository.save(session);
        }

        OpenAttendanceSessionDTO dto = new OpenAttendanceSessionDTO();
        dto.setAttendanceLogId(session.getId());
        dto.setEmployeePk(employee.getId());
        dto.setEmployeeId(employee.getEmployeeId());
        dto.setFullName(((employee.getFirstName() == null ? "" : employee.getFirstName()) + " "
                + (employee.getLastName() == null ? "" : employee.getLastName())).trim());
        dto.setCheckInTime(AppTimeZone.toOffsetDateTime(session.getCheckInTime()));
        dto.setShiftType(session.getShiftType());
        dto.setStatus(effectiveStatus);
        dto.setManualOverride(Boolean.TRUE.equals(session.getManualOverride()));
        return dto;
    }

    private AttendanceLogDTO toLogDTO(AttendanceLog session) {
        AttendanceLogDTO dto = new AttendanceLogDTO();
        dto.setId(session.getId());
        dto.setEmployeeId(session.getEmployeeId());
        dto.setCheckInTime(AppTimeZone.toOffsetDateTime(session.getCheckInTime()));
        dto.setCheckOutTime(AppTimeZone.toOffsetDateTime(session.getCheckOutTime()));
        dto.setDeviceId(session.getDeviceId());
        dto.setDoorId(session.getDoorId());
        dto.setEventType(session.getEventType());
        dto.setVerificationMethod(session.getVerificationMethod());
        dto.setStatus(session.getStatus());
        dto.setCreatedAt(AppTimeZone.toOffsetDateTime(session.getCreatedAt()));
        return dto;
    }

    private boolean isInBranchScope(Employee employee, Long branchScope) {
        return branchScope == null || (employee.getBranchId() != null && branchScope.equals(employee.getBranchId()));
    }

    private String shiftFor(AttendanceLog session, Employee employee, java.time.LocalDateTime entry) {
        if (session.getShiftType() != null && !session.getShiftType().isBlank()) return session.getShiftType();
        return scheduleResolver.resolve(employee, entry.toLocalDate()).shiftType();
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null
                ? authentication.getName()
                : "system";
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required");
        }
        return tenantId;
    }
}
