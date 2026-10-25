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

    @Test void mainLegacyAmountsAndRefundsSurviveAcrossTypesAndOldDates() {
        var employee = employee(); employee.setAnnualLeaveBalance(12); employee.setLegacyLeaveBalanceOffset(7);
        var chargedSick = leave("2026-01-01", "2026-01-04");
        chargedSick.setLegacyBalanceAccounting(true); chargedSick.setBalanceChargedDays(4);
        var chargedUnpaid = leave("2026-01-05", "2026-01-07");
        chargedUnpaid.setLegacyBalanceAccounting(true); chargedUnpaid.setBalanceChargedDays(3);
        var unchargedAnnual = leave("2026-11-01", "2026-11-05");
        unchargedAnnual.setLegacyBalanceAccounting(true); unchargedAnnual.setBalanceChargedDays(0);
        var records = List.of(chargedSick, chargedUnpaid, unchargedAnnual);
        assertEquals(12, LeaveBalanceService.calculate(employee, records).remainingDays());
        chargedSick.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        assertEquals(16, LeaveBalanceService.calculate(employee, records).remainingDays());
        unchargedAnnual.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        assertEquals(16, LeaveBalanceService.calculate(employee, records).remainingDays());
        chargedUnpaid.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        assertEquals(19, LeaveBalanceService.calculate(employee, records).remainingDays());
        assertEquals(12, employee.getAnnualLeaveBalance()); // Projection never rewrites original customer amount.
    }

    @Test void legacyChargesDoNotCoverNewTypeIntervalsAndUnknownOpeningStaysUnknown() {
        var employee = employee(); employee.setAnnualLeaveBalance(12); employee.setLegacyLeaveBalanceOffset(4);
        var legacy = leave("2026-11-01", "2026-11-05");
        legacy.setLegacyBalanceAccounting(true); legacy.setBalanceChargedDays(4);
        assertEquals(10, LeaveBalanceService.calculate(employee,
                List.of(legacy, leave("2026-11-01", "2026-11-02"))).remainingDays());
        employee.setAnnualLeaveBalance(null);
        assertNull(LeaveBalanceService.calculate(employee, List.of(legacy)).remainingDays());
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
        var unpaid = new LeaveType(); unpaid.setId(3L); unpaid.setLeaveCode("UNPAID");
        var sickLeave = leave("2026-10-19", "2026-10-20"); sickLeave.setLeaveTypeId(2L);
        var unpaidLeave = leave("2026-11-01", "2026-11-03"); unpaidLeave.setLeaveTypeId(3L);
        when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(3L)).thenReturn(List.of(employee()));
        when(types.findByTenantId(3L)).thenReturn(List.of(annual, sick, unpaid));
        when(requests.findByTenantIdAndStatus(3L, LeaveRequest.LeaveStatus.APPROVED)).thenReturn(List.of(sickLeave, unpaidLeave));
        try {
            TenantContext.setTenantId(3L);
            var balance = new LeaveBalanceService(employees, requests, types).getAll().get(0);
            assertEquals(20, balance.remainingDays());
            assertEquals(0, balance.usedDays());
            assertEquals(3, balance.unpaidDays());
        } finally { TenantContext.clear(); }
        verify(requests).findByTenantIdAndStatus(3L, LeaveRequest.LeaveStatus.APPROVED);
    }

    @Test void everyCustomTypeIsTrackedAndDebitTypesShareRemainingBalance() {
        var employees = mock(EmployeeRepository.class);
        var requests = mock(LeaveRequestRepository.class);
        var types = mock(LeaveTypeRepository.class);
        var charged = new LeaveType(); charged.setId(1L); charged.setShortName("TƏ");
        charged.setLeaveName("Təhsil"); charged.setDeductFromBalance(true);
        var free = new LeaveType(); free.setId(2L); free.setShortName("X"); free.setLeaveName("Xüsusi");
        var usedFree = leave("2026-10-22", "2026-10-24"); usedFree.setLeaveTypeId(2L);
        when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(3L)).thenReturn(List.of(employee()));
        when(types.findByTenantId(3L)).thenReturn(List.of(charged, free));
        when(requests.findByTenantIdAndStatus(3L, LeaveRequest.LeaveStatus.APPROVED))
                .thenReturn(List.of(leave("2026-10-19", "2026-10-20"), usedFree));
        TenantContext.setTenantId(3L);
        try {
            var usage = new LeaveBalanceService(employees, requests, types).getUsage(7L);
            assertEquals(2, usage.get(0).usedDays());
            assertEquals(18, usage.get(0).remainingDays());
            assertEquals(3, usage.get(1).usedDays());
            assertNull(usage.get(1).remainingDays());
            assertTrue(new LeaveBalanceService(employees, requests, types).getUsage(999L).isEmpty());
        } finally { TenantContext.clear(); }
    }

    @Test void unpaidAccumulatesWithoutDebitingAndCancellationRemovesItsDays() {
        var paid = leave("2026-11-01", "2026-11-05");
        var unpaid = leave("2026-11-10", "2026-11-12");
        var balance = LeaveBalanceService.calculate(employee(), List.of(paid), List.of(unpaid));
        assertEquals(5, balance.usedDays());
        assertEquals(15, balance.remainingDays());
        assertEquals(3, balance.unpaidDays());
        unpaid.setStatus(LeaveRequest.LeaveStatus.REJECTED);
        balance = LeaveBalanceService.calculate(employee(), List.of(paid), List.of(unpaid));
        assertEquals(15, balance.remainingDays());
        assertEquals(0, balance.unpaidDays());
    }

    @Test void unpaidAtZeroBalanceStillCountsAndClipsToActivationWithoutDoubleCounting() {
        Employee employee = employee(); employee.setAnnualLeaveBalance(0);
        var overlap = leave("2026-10-19", "2026-10-22");
        var pending = leave("2026-11-01", "2026-11-02"); pending.setStatus(LeaveRequest.LeaveStatus.PENDING);
        var balance = LeaveBalanceService.calculate(employee, List.of(), List.of(
                leave("2026-10-17", "2026-10-20"), overlap, overlap, pending));
        assertEquals(0, balance.usedDays());
        assertEquals(0, balance.remainingDays());
        assertEquals(4, balance.unpaidDays());
    }
}
