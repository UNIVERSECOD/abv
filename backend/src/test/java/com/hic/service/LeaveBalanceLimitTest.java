package com.hic.service;

import com.hic.dto.LeaveRequestDTO;
import com.hic.exception.BadRequestException;
import com.hic.model.Employee;
import com.hic.model.LeaveRequest;
import com.hic.model.LeaveRequest.LeaveStatus;
import com.hic.model.LeaveType;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveBalanceLimitTest {
    final EmployeeRepository employees = mock(EmployeeRepository.class);
    final LeaveRequestRepository requests = mock(LeaveRequestRepository.class);
    final LeaveTypeRepository types = mock(LeaveTypeRepository.class);
    final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    final LeaveService service = new LeaveService(requests, employees, types, entityManager);
    Employee employee;

    @BeforeEach void setup() {
        TenantContext.setTenantId(7L);
        employee = new Employee(); employee.setId(11L); employee.setTenantId(7L);
        employee.setAnnualLeaveBalance(5); employee.setLeaveBalanceStartDate(LocalDate.of(2026, 10, 20));
        when(employees.lockForLeave(7L, 11L)).thenReturn(Optional.of(employee));
        when(employees.findByTenantIdAndId(7L, 11L)).thenReturn(Optional.of(employee));
        var paid = new LeaveType(); paid.setId(1L); paid.setLeaveCode("ANNUAL");
        var unpaid = new LeaveType(); unpaid.setId(2L); unpaid.setLeaveCode("UNPAID");
        when(types.findByTenantId(7L)).thenReturn(List.of(paid, unpaid));
        when(types.lockForLeave(eq(7L), anyLong())).thenAnswer(call -> types.findByTenantId(7L).stream()
                .filter(type -> type.getId().equals(call.getArgument(1))).findFirst());
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    LeaveRequestDTO dto(int days) {
        var dto = new LeaveRequestDTO(); dto.setEmployeeId(11L); dto.setLeaveTypeId(1L);
        dto.setStatus(LeaveStatus.APPROVED); dto.setStartDate(LocalDate.of(2026, 11, 1));
        dto.setEndDate(dto.getStartDate().plusDays(days - 1)); return dto;
    }

    LeaveRequest existing(int days) {
        var r = new LeaveRequest(); r.setId(99L); r.setTenantId(7L); r.setEmployeeId(11L);
        r.setLeaveTypeId(1L); r.setStatus(LeaveStatus.APPROVED);
        r.setStartDate(LocalDate.of(2026, 10, 20)); r.setEndDate(r.getStartDate().plusDays(days - 1));
        return r;
    }

    @Test void rejectsPaidAboveBalanceWithoutWriting() {
        assertThatThrownBy(() -> service.create(dto(6))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Qalıq: 5 gün, tələb olunan: 6 gün");
        verify(requests, never()).save(any());
        verify(entityManager).refresh(employee, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test void allowsExactBalance() {
        assertThat(service.create(dto(5)).getStatus()).isEqualTo(LeaveStatus.APPROVED);
    }

    @Test void customTypeDeductsFromTheSameBalanceAsAnnual() {
        var custom = new LeaveType(); custom.setId(3L); custom.setLeaveCode("CUSTOM_TEST");
        custom.setShortName("TƏ"); custom.setDeductFromBalance(true);
        var annual = new LeaveType(); annual.setId(1L); annual.setLeaveCode("ANNUAL");
        when(types.findByTenantId(7L)).thenReturn(List.of(custom, annual));
        when(requests.findByTenantIdAndEmployeeId(7L, 11L)).thenReturn(List.of(existing(3)));
        var payload = dto(3); payload.setLeaveTypeId(3L);
        assertThatThrownBy(() -> service.create(payload)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Qalıq: 2 gün");
        verify(requests, never()).save(any());
    }

    @Test void waitingCreationRefreshesTypePolicyBeforeCheckingBalance() {
        var type = new LeaveType(); type.setId(3L); type.setLeaveCode("CUSTOM_TEST");
        type.setShortName("TƏ"); type.setDeductFromBalance(false);
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
        doAnswer(call -> { type.setDeductFromBalance(true); return null; })
                .when(entityManager).refresh(type, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var payload = dto(6); payload.setLeaveTypeId(3L);

        assertThatThrownBy(() -> service.create(payload)).hasMessageContaining("Qalıq: 5 gün");

        verify(types).lockForLeave(7L, 3L);
        verify(entityManager).refresh(type, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        verify(requests, never()).save(any());
    }

    @Test void waitingCreationCannotUseATypeArchivedWhileItWaited() {
        var type = new LeaveType(); type.setId(3L); type.setLeaveCode("CUSTOM_TEST"); type.setShortName("TƏ");
        when(types.findByTenantId(7L)).thenReturn(List.of(type));
        doAnswer(call -> { type.setArchived(true); return null; })
                .when(entityManager).refresh(type, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var payload = dto(1); payload.setLeaveTypeId(3L);

        assertThatThrownBy(() -> service.create(payload)).hasMessageContaining("Silinmiş məzuniyyət növünü");
        verify(requests, never()).save(any());
    }

    @Test void bulkTypeLocksAreDistinctAndSorted() {
        service.lockTypes(7L, List.of(2L, 1L, 2L));
        var order = inOrder(types);
        order.verify(types).lockForLeave(7L, 1L);
        order.verify(types).lockForLeave(7L, 2L);
        verify(types, times(1)).lockForLeave(7L, 1L);
        verify(types, times(1)).lockForLeave(7L, 2L);
    }

    @Test void subtractsAlreadyApprovedUsageAndIgnoresPendingUnpaid() {
        var approved = existing(3);
        when(requests.findByTenantIdAndEmployeeId(7L, 11L)).thenReturn(List.of(approved));
        assertThatThrownBy(() -> service.create(dto(3))).hasMessageContaining("Qalıq: 2 gün");
        assertThat(service.create(dto(2)).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        approved.setStatus(LeaveStatus.PENDING);
        assertThat(service.create(dto(5)).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        approved.setStatus(LeaveStatus.APPROVED); approved.setLeaveTypeId(2L);
        assertThat(service.create(dto(5)).getStatus()).isEqualTo(LeaveStatus.APPROVED);
    }

    @Test void unpaidIsAllowedAtZeroAndUnknownBalance() {
        var dto = dto(100); dto.setLeaveTypeId(2L);
        employee.setAnnualLeaveBalance(0);
        assertThat(service.create(dto).getLeaveTypeId()).isEqualTo(2L);
        employee.setAnnualLeaveBalance(null);
        assertThat(service.create(dto).getLeaveTypeId()).isEqualTo(2L);
    }

    @Test void missingPaidBalanceCannotApproveButCanSavePending() {
        employee.setAnnualLeaveBalance(null);
        assertThatThrownBy(() -> service.create(dto(1))).hasMessageContaining("başlanğıc qalığı daxil edilməyib");
        var dto = dto(10); dto.setStatus(LeaveStatus.PENDING);
        assertThat(service.create(dto).getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test void reapprovalAndEditExcludeOwnOldUsage() {
        var old = existing(5);
        when(requests.lockInTenant(99L, 7L)).thenReturn(Optional.of(old));
        when(requests.findByTenantIdAndEmployeeId(7L, 11L)).thenReturn(List.of(old));
        assertThat(service.updateStatus(99L, LeaveStatus.APPROVED, null).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(service.update(99L, dto(5)).getEndDate()).isEqualTo(dto(5).getEndDate());
        assertThatThrownBy(() -> service.update(99L, dto(6))).hasMessageContaining("Qalıq: 5 gün");
    }

    @Test void omittedStatusOnApprovedEditCannotBypassBalanceCheck() {
        var old = existing(1);
        when(requests.lockInTenant(99L, 7L)).thenReturn(Optional.of(old));
        var dto = dto(6); dto.setStatus(null);
        assertThatThrownBy(() -> service.update(99L, dto)).hasMessageContaining("balansı kifayət etmir");
        verify(requests, never()).save(any());
    }

    @Test void pendingApprovalRechecksCurrentBalance() {
        var old = existing(6); old.setStatus(LeaveStatus.PENDING);
        when(requests.lockInTenant(99L, 7L)).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> service.updateStatus(99L, LeaveStatus.APPROVED, null))
                .hasMessageContaining("tələb olunan: 6 gün");
        assertThat(old.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test void cancellationIsAllowedForPreviouslyOverdrawnBalance() {
        var old = existing(10); employee.setAnnualLeaveBalance(0);
        when(requests.lockInTenant(99L, 7L)).thenReturn(Optional.of(old));
        assertThat(service.updateStatus(99L, LeaveStatus.REJECTED, null).getStatus()).isEqualTo(LeaveStatus.REJECTED);
    }

    @Test void onlyDaysAfterActivationAreCharged() {
        employee.setAnnualLeaveBalance(1);
        var dto = dto(3); dto.setStartDate(LocalDate.of(2026, 10, 18)); dto.setEndDate(LocalDate.of(2026, 10, 20));
        assertThat(service.create(dto).getStatus()).isEqualTo(LeaveStatus.APPROVED);
        dto.setEndDate(LocalDate.of(2026, 10, 21));
        assertThatThrownBy(() -> service.create(dto)).hasMessageContaining("tələb olunan: 2 gün");
    }
}
