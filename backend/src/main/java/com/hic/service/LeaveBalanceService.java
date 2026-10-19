package com.hic.service;

import com.hic.model.Employee;
import com.hic.model.LeaveRequest;
import com.hic.model.LeaveType;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Live projection: edits, imports and cancellation cannot leave a stale balance. */
@Service
@RequiredArgsConstructor
public class LeaveBalanceService {
    private final EmployeeRepository employees;
    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;

    public record Balance(Long employeeId, String employeeCode, String employeeName,
                          LocalDate startDate, Integer openingDays, int usedDays,
                          Integer remainingDays) { }

    @Transactional(readOnly = true)
    public List<Balance> getAll() {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("Tenant context is missing");
        Set<Long> annualTypes = types.findByTenantId(tenant).stream()
                .filter(t -> "ANNUAL".equalsIgnoreCase(t.getLeaveCode()))
                .map(LeaveType::getId).collect(Collectors.toSet());
        Map<Long, List<LeaveRequest>> approved = requests
                .findByTenantIdAndStatus(tenant, LeaveRequest.LeaveStatus.APPROVED).stream()
                .filter(r -> annualTypes.contains(r.getLeaveTypeId()))
                .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));
        return employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenant).stream()
                .map(e -> calculate(e, approved.getOrDefault(e.getId(), List.of()))).toList();
    }

    static Balance calculate(Employee employee, List<LeaveRequest> requests) {
        LocalDate openingDate = employee.getLeaveBalanceStartDate();
        // Merge intervals before counting: legacy overlapping records must not debit twice.
        List<LocalDate[]> intervals = requests.stream()
                .filter(r -> r.getStatus() == LeaveRequest.LeaveStatus.APPROVED)
                .filter(r -> !r.getEndDate().isBefore(openingDate))
                .map(r -> new LocalDate[]{r.getStartDate().isBefore(openingDate)
                        ? openingDate : r.getStartDate(), r.getEndDate()})
                .sorted(Comparator.comparing(a -> a[0])).toList();
        int used = 0;
        LocalDate last = null;
        for (LocalDate[] range : intervals) {
            LocalDate start = last != null && !range[0].isAfter(last) ? last.plusDays(1) : range[0];
            LocalDate end = range[1];
            if (start.isAfter(end)) continue;
            used += Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);
            last = end;
        }
        Integer opening = employee.getAnnualLeaveBalance();
        return new Balance(employee.getId(), employee.getEmployeeId(),
                employee.getLastName() + " " + employee.getFirstName(), openingDate,
                opening, used, opening == null ? null : opening - used);
    }
}
