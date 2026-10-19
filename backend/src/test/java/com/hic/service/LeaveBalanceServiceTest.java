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
                leave("2026-10-01", "2026-10-18"), leave("2026-10-18", "2026-10-21")), today);
        assertEquals(1, result.usedDays());
        assertEquals(2, result.plannedDays());
        assertEquals(19, result.remainingDays());
        assertEquals(17, result.availableDays());
    }

    @Test void futureDaysAcrossYearBoundaryRemainReservedWithoutInventingAnnualCredit() {
        var result = LeaveBalanceService.calculate(employee(), List.of(leave("2026-12-30", "2027-01-02")), today);
        assertEquals(0, result.usedDays());
        assertEquals(4, result.plannedDays());
        assertEquals(16, result.availableDays());
    }

    @Test void legacyOverlapsAndDuplicatesAreCountedOnce() {
        var result = LeaveBalanceService.calculate(employee(), List.of(
                leave("2026-10-19", "2026-10-22"), leave("2026-10-20", "2026-10-21"),
                leave("2026-10-21", "2026-10-24")), today);
        assertEquals(1, result.usedDays());
        assertEquals(5, result.plannedDays());
    }

    @Test void cancellationRestoresBalanceAndPendingDoesNotDebit() {
        var rejected = leave("2026-10-19", "2026-10-22");
        rejected.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        var pending = leave("2026-10-23", "2026-10-25");
        pending.setStatus(LeaveRequest.LeaveStatus.PENDING);
        var result = LeaveBalanceService.calculate(employee(), List.of(rejected, pending), today);
        assertEquals(20, result.availableDays());
    }

    @Test void missingOpeningBalanceIsNotSilentlyAssumedAndOverdraftIsVisible() {
        Employee employee = employee();
        employee.setAnnualLeaveBalance(null);
        assertNull(LeaveBalanceService.calculate(employee, List.of(), today).remainingDays());
        employee.setAnnualLeaveBalance(0);
        assertEquals(-1, LeaveBalanceService.calculate(employee,
                List.of(leave("2026-10-19", "2026-10-19")), today).remainingDays());
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
            assertEquals(20, new LeaveBalanceService(employees, requests, types).getAll().get(0).availableDays());
        } finally { TenantContext.clear(); }
        verify(requests).findByTenantIdAndStatus(3L, LeaveRequest.LeaveStatus.APPROVED);
    }
}
