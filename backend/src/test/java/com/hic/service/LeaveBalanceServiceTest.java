package com.hic.service;

import com.hic.model.Employee;
import com.hic.model.LeaveRequest;
import com.hic.model.LeaveType;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveBalanceServiceTest {
    private final LocalDate today = LocalDate.of(2026, 10, 19);

    private Employee employee() {
        Employee employee = new Employee();
        employee.setId(7L);
        employee.setAnnualLeaveBalance(20);
        employee.setLeaveBalanceStartDate(today);
        return employee;
    }

    private LeaveRequest leave(String start, String end) {
        LeaveRequest leave = new LeaveRequest();
        leave.setEmployeeId(7L);
        leave.setLeaveTypeId(1L);
        leave.setStartDate(LocalDate.parse(start));
        leave.setEndDate(LocalDate.parse(end));
        leave.setStatus(LeaveRequest.LeaveStatus.APPROVED);
        return leave;
    }

    @Test void openingBalanceIsPreservedAndEarlierDaysAreNotDeducted() {
        var result = LeaveBalanceService.calculate(employee(), List.of(
                leave("2026-10-01", "2026-10-18"), leave("2026-10-18", "2026-10-21")));
        assertEquals(3, result.usedDays());
        assertEquals(17, result.remainingDays());
    }

    @Test void futureDaysAcrossYearBoundaryAreImmediatelyDebitedWithoutInventingAnnualCredit() {
        var result = LeaveBalanceService.calculate(employee(), List.of(leave("2026-12-30", "2027-01-02")));
        assertEquals(4, result.usedDays());
        assertEquals(16, result.remainingDays());
    }

    @Test void legacyOverlapsAndDuplicatesAreCountedOnce() {
        var result = LeaveBalanceService.calculate(employee(), List.of(
                leave("2026-10-19", "2026-10-22"), leave("2026-10-20", "2026-10-21"),
                leave("2026-10-21", "2026-10-24")));
        assertEquals(6, result.usedDays());
    }

    @Test void cancellationRestoresBalanceAndPendingDoesNotDebit() {
        var rejected = leave("2026-10-19", "2026-10-22");
        rejected.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        var pending = leave("2026-10-23", "2026-10-25");
        pending.setStatus(LeaveRequest.LeaveStatus.PENDING);
        var result = LeaveBalanceService.calculate(employee(), List.of(rejected, pending));
        assertEquals(20, result.remainingDays());
    }

    @Test void futureApprovalAndCancellationImmediatelyChangeUsedAndRemaining() {
        var request = leave("2026-11-01", "2026-11-05");
        var approved = LeaveBalanceService.calculate(employee(), List.of(request));
        assertEquals(5, approved.usedDays());
        assertEquals(15, approved.remainingDays());
        request.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        var cancelled = LeaveBalanceService.calculate(employee(), List.of(request));
        assertEquals(0, cancelled.usedDays());
        assertEquals(20, cancelled.remainingDays());
    }

    @Test void missingOpeningBalanceIsNotSilentlyAssumedAndOverdraftIsVisible() {
        Employee employee = employee();
        employee.setAnnualLeaveBalance(null);
        assertNull(LeaveBalanceService.calculate(employee, List.of()).remainingDays());
        employee.setAnnualLeaveBalance(0);
        assertEquals(-1, LeaveBalanceService.calculate(employee,
                List.of(leave("2026-10-19", "2026-10-19"))).remainingDays());
    }

    @Test void readsAreTenantScopedAndOnlyAnnualLeaveIsDebited() {
        var employees = mock(EmployeeRepository.class);
        var requests = mock(LeaveRequestRepository.class);
        var types = mock(LeaveTypeRepository.class);
        var annual = new LeaveType(); annual.setId(1L); annual.setLeaveCode("ANNUAL");
        var sick = new LeaveType(); sick.setId(2L); sick.setLeaveCode("SICK");
        var sickLeave = leave("2026-10-19", "2026-10-20"); sickLeave.setLeaveTypeId(2L);
        when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(3L)).thenReturn(List.of(employee()));
        when(types.findByTenantId(3L)).thenReturn(List.of(annual, sick));
        when(requests.findByTenantIdAndStatus(3L, LeaveRequest.LeaveStatus.APPROVED)).thenReturn(List.of(sickLeave));
        try {
            TenantContext.setTenantId(3L);
            assertEquals(20, new LeaveBalanceService(employees, requests, types).getAll().get(0).remainingDays());
        } finally { TenantContext.clear(); }
        verify(requests).findByTenantIdAndStatus(3L, LeaveRequest.LeaveStatus.APPROVED);
    }
}
