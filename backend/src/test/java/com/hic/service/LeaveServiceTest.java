package com.hic.service;

import com.hic.model.LeaveRequest;
import com.hic.model.Employee;
import com.hic.model.LeaveType;
import com.hic.dto.LeaveRequestDTO;
import com.hic.util.TenantContext;
import com.hic.util.AppTimeZone;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import com.hic.model.LeaveRequest.LeaveStatus;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.LeaveTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock
    private LeaveRequestRepository leaveRequestRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private LeaveTypeRepository leaveTypeRepository;
    @Mock
    private jakarta.persistence.EntityManager entityManager;

    @InjectMocks
    private LeaveService leaveService;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private LeaveRequestDTO payload() {
        var dto = new LeaveRequestDTO();
        dto.setEmployeeId(11L); dto.setLeaveTypeId(3L);
        dto.setStartDate(LocalDate.of(2026, 10, 18)); dto.setEndDate(LocalDate.of(2026, 10, 20));
        dto.setStatus(LeaveStatus.APPROVED);
        return dto;
    }

    private void validReferences() {
        TenantContext.setTenantId(7L);
        var employee = new Employee(); employee.setId(11L); employee.setTenantId(7L);
        employee.setEmployeeId("HR-11"); employee.setFirstName("Test"); employee.setLastName("Person");
        var type = new LeaveType(); type.setId(3L); type.setTenantId(7L); type.setLeaveName("İllik məzuniyyət");
        when(employeeRepository.findByTenantIdAndId(7L, 11L)).thenReturn(Optional.of(employee));
        when(employeeRepository.lockForLeave(7L, 11L)).thenReturn(Optional.of(employee));
        when(leaveTypeRepository.lockForLeave(7L, 3L)).thenReturn(Optional.of(type));
        when(leaveTypeRepository.findByTenantId(7L)).thenReturn(List.of(type));
    }

    @Test
    void createApprovedLeaveUsesTenantAndBakuApprovalDate() {
        validReferences();
        when(leaveRequestRepository.save(any())).thenAnswer(call -> {
            LeaveRequest req = call.getArgument(0); req.setId(99L);
            assertThat(req.getTenantId()).isEqualTo(7L);
            return req;
        });
        var result = leaveService.create(payload());
        assertThat(result.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(result.getApprovalDate()).isEqualTo(AppTimeZone.today());
        assertThat(result.getEmployeeCode()).isEqualTo("HR-11");
        assertThat(result.getLeaveTypeName()).isEqualTo("İllik məzuniyyət");
    }

    @Test
    void createRejectsEmployeeFromAnotherTenant() {
        TenantContext.setTenantId(7L);
        assertThrows(BadRequestException.class, () -> leaveService.create(payload()));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void createRejectsLeaveTypeFromAnotherTenant() {
        TenantContext.setTenantId(7L);
        when(employeeRepository.lockForLeave(7L, 11L)).thenReturn(Optional.of(new Employee()));
        assertThrows(BadRequestException.class, () -> leaveService.create(payload()));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void createRejectsInclusiveOverlapAndInvalidDates() {
        validReferences();
        var existing = new LeaveRequest(); existing.setId(9L); existing.setLeaveTypeId(3L); existing.setStatus(LeaveStatus.APPROVED);
        existing.setStartDate(LocalDate.of(2026, 10, 20)); existing.setEndDate(LocalDate.of(2026, 10, 22));
        when(leaveRequestRepository.findByTenantIdAndEmployeeId(7L, 11L)).thenReturn(List.of(existing));
        assertThrows(BadRequestException.class, () -> leaveService.create(payload()));
        var invalid = payload(); invalid.setEndDate(invalid.getStartDate().minusDays(1));
        assertThrows(BadRequestException.class, () -> leaveService.create(invalid));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void statusUpdateCannotReachAnotherTenant() {
        TenantContext.setTenantId(7L);
        assertThrows(ResourceNotFoundException.class, () -> leaveService.updateStatus(8L, LeaveStatus.APPROVED, null));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void exactDuplicateOfCancelledLeaveMustBeEditedRatherThanInsertedAgain() {
        validReferences();
        var existing = new LeaveRequest(); existing.setId(9L); existing.setLeaveTypeId(3L);
        existing.setStatus(LeaveStatus.REJECTED); existing.setStartDate(payload().getStartDate());
        existing.setEndDate(payload().getEndDate());
        when(leaveRequestRepository.findByTenantIdAndEmployeeId(7L, 11L)).thenReturn(List.of(existing));
        var duplicate = payload(); duplicate.setStatus(LeaveStatus.REJECTED);
        assertThrows(BadRequestException.class, () -> leaveService.create(duplicate));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void rejectedLeaveCanBeCancelledWithoutDeletingHistory() {
        TenantContext.setTenantId(7L);
        var existing = new LeaveRequest(); existing.setId(8L); existing.setTenantId(7L);
        existing.setEmployeeId(11L); existing.setLeaveTypeId(3L); existing.setStatus(LeaveStatus.APPROVED);
        when(leaveRequestRepository.lockInTenant(8L, 7L)).thenReturn(Optional.of(existing));
        when(employeeRepository.lockForLeave(7L, 11L)).thenReturn(Optional.of(new Employee()));
        when(leaveRequestRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = leaveService.updateStatus(8L, LeaveStatus.REJECTED, 999L);
        assertThat(result.getStatus()).isEqualTo(LeaveStatus.REJECTED);
        assertThat(result.getApprovedBy()).isNull();
        verify(leaveRequestRepository, never()).deleteById(any());
    }

    @Test
    void hasActiveLeave_returnsTrueForDateInsideApprovedRange() {
        LeaveRequest request = new LeaveRequest();
        request.setStatus(LeaveStatus.APPROVED);
        request.setStartDate(LocalDate.of(2026, 5, 10));
        request.setEndDate(LocalDate.of(2026, 5, 12));
        when(leaveRequestRepository.findByEmployeeIdAndStatus(1L, LeaveStatus.APPROVED)).thenReturn(List.of(request));

        boolean active = leaveService.hasActiveLeave(1L, LocalDate.of(2026, 5, 11));

        assertThat(active).isTrue();
    }

    @Test
    void hasActiveLeave_returnsFalseWhenNoApprovedLeaveIncludesDate() {
        LeaveRequest request = new LeaveRequest();
        request.setStatus(LeaveStatus.APPROVED);
        request.setStartDate(LocalDate.of(2026, 5, 10));
        request.setEndDate(LocalDate.of(2026, 5, 12));
        when(leaveRequestRepository.findByEmployeeIdAndStatus(1L, LeaveStatus.APPROVED)).thenReturn(List.of(request));

        boolean active = leaveService.hasActiveLeave(1L, LocalDate.of(2026, 5, 20));

        assertThat(active).isFalse();
    }
}
