package com.hic.service;

import com.hic.dto.AttendanceCorrectionRequest;
import com.hic.model.AttendanceLog;
import com.hic.model.AttendanceLogAdjustment;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogAdjustmentRepository;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceSessionManagementServiceTest {

    @Mock private AttendanceLogRepository attendanceLogRepository;
    @Mock private AttendanceLogAdjustmentRepository adjustmentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private AttendanceService attendanceService;
    @Mock private AttendanceSessionPolicy sessionPolicy;
    @Mock private UserScopeService userScopeService;
    @Mock private AttendanceScheduleResolver scheduleResolver;

    private AttendanceSessionManagementService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new AttendanceSessionManagementService(
                attendanceLogRepository,
                adjustmentRepository,
                employeeRepository,
                calculationService,
                attendanceService,
                sessionPolicy,
                userScopeService,
                scheduleResolver
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getOpenSessions_returnsRequestedTenantScopedPage() {
        AttendanceLog session = new AttendanceLog();
        session.setId(10L);
        session.setEmployeeId(5L);
        session.setCheckInTime(LocalDate.of(2026, 11, 6).atTime(9, 0));
        session.setStatus("OPEN");
        Employee employee = new Employee();
        employee.setId(5L);
        employee.setTenantId(1L);
        employee.setEmployeeId("BAOFIS-1001");
        employee.setFirstName("Test");
        employee.setLastName("Employee");
        when(userScopeService.resolveBranchScope(null)).thenReturn(7L);
        when(attendanceLogRepository.findOpenSessions(1L, 7L, PageRequest.of(1, 10)))
                .thenReturn(new PageImpl<>(List.of(session), PageRequest.of(1, 10), 21));
        when(employeeRepository.findAllById(any())).thenReturn(List.of(employee));
        when(sessionPolicy.effectiveStatus(employee, session)).thenReturn("OPEN");

        var result = service.getOpenSessions(1, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(21);
        assertThat(result.getCurrentPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(10);
    }

    @Test
    void correctSession_keepsAuditAndRecalculatesAffectedWorkDates() {
        LocalDate workDate = LocalDate.of(2026, 9, 23);
        AttendanceLog session = new AttendanceLog();
        session.setId(10L);
        session.setTenantId(1L);
        session.setEmployeeId(5L);
        session.setShiftType("STANDARD");
        session.setCheckInTime(workDate.atTime(20, 0));

        Employee employee = new Employee();
        employee.setId(5L);
        employee.setTenantId(1L);

        AttendanceCorrectionRequest request = new AttendanceCorrectionRequest();
        request.setCheckInTime(workDate.atTime(20, 0));
        request.setCheckOutTime(workDate.plusDays(1).atTime(4, 0));
        request.setReason("Device exit was not received");

        when(attendanceLogRepository.findByTenantIdAndId(any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, Long.class)).isEqualTo(1L);
            assertThat(invocation.getArgument(1, Long.class)).isEqualTo(10L);
            return Optional.of(session);
        });
        when(employeeRepository.findByTenantIdAndId(any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, Long.class)).isEqualTo(1L);
            assertThat(invocation.getArgument(1, Long.class)).isEqualTo(5L);
            return Optional.of(employee);
        });
        when(userScopeService.resolveBranchScope(null)).thenReturn(null);
        when(attendanceLogRepository.save(any(AttendanceLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.correctSession(10L, request);

        assertThat(session.getCheckOutTime()).isEqualTo(workDate.plusDays(1).atTime(4, 0));
        assertThat(session.getManualOverride()).isTrue();
        assertThat(session.getStatus()).isEqualTo("MANUALLY_CORRECTED");

        ArgumentCaptor<AttendanceLogAdjustment> audit = ArgumentCaptor.forClass(AttendanceLogAdjustment.class);
        verify(adjustmentRepository).save(audit.capture());
        assertThat(audit.getValue().getReason()).isEqualTo("Device exit was not received");
        verify(calculationService).calculateForDay(5L, workDate);
        verify(attendanceService).generateDailySummary(5L, workDate);
        verify(calculationService, never()).calculateForDay(5L, workDate.plusDays(1));
        verify(attendanceService, never()).generateDailySummary(5L, workDate.plusDays(1));
    }

    @Test
    void shorteningFlexibleOvernightSessionRecalculatesRemovedCarryInDay() {
        var day = LocalDate.of(2026, 9, 30);
        var session = new AttendanceLog();
        session.setId(10L);
        session.setEmployeeId(5L);
        session.setCheckInTime(day.atTime(20, 0));
        session.setCheckOutTime(day.plusDays(1).atTime(2, 0));
        var employee = new Employee();
        employee.setId(5L);
        when(scheduleResolver.resolve(employee, day)).thenReturn(new AttendanceScheduleResolver.DaySchedule(
                1L, "FLEXIBLE", true, null, null, 0, 0, 0));
        when(attendanceLogRepository.findByTenantIdAndId(1L, 10L)).thenReturn(Optional.of(session));
        when(employeeRepository.findByTenantIdAndId(1L, 5L)).thenReturn(Optional.of(employee));
        when(userScopeService.resolveBranchScope(null)).thenReturn(null);
        when(attendanceLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var request = new AttendanceCorrectionRequest();
        request.setCheckInTime(day.atTime(20, 0));
        request.setCheckOutTime(day.atTime(23, 0));
        request.setReason("Synthetic correction");
        service.correctSession(10L, request);
        verify(calculationService).calculateForDay(5L, day);
        verify(calculationService).calculateForDay(5L, day.plusDays(1));
        verify(attendanceService).generateDailySummary(5L, day.plusDays(1));
    }
}
