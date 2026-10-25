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
                          Integer remainingDays, int unpaidDays) { }

    public record TypeUsage(Long employeeId, String employeeCode, String employeeName, Long leaveTypeId,
                            String leaveTypeName, String shortName, boolean deductFromBalance,
                            int usedDays, Integer remainingDays) { }

    @Transactional(readOnly = true)
    public List<TypeUsage> getUsage(Long employeeId) {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("Tenant context is missing");
        var leaveTypes = types.findByTenantId(tenant);
        var approved = requests.findByTenantIdAndStatus(tenant, LeaveRequest.LeaveStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));
        List<TypeUsage> result = new ArrayList<>();
        for (var employee : employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenant)) {
            if (employeeId != null && !employeeId.equals(employee.getId())) continue;
            var employeeRequests = approved.getOrDefault(employee.getId(), List.of());
            var debitIds = leaveTypes.stream().filter(LeaveType::debitsBalance).map(LeaveType::getId).collect(Collectors.toSet());
            int charged = countChargedDays(employeeRequests.stream()
                    .filter(r -> r.isLegacyBalanceAccounting() || debitIds.contains(r.getLeaveTypeId())).toList(),
                    employee.getLeaveBalanceStartDate());
            Integer remaining = employee.openingLeaveBalance() == null ? null : employee.openingLeaveBalance() - charged;
            for (var type : leaveTypes) {
                var typeRequests = employeeRequests.stream().filter(r -> type.getId().equals(r.getLeaveTypeId())).toList();
                if (type.isArchived() && typeRequests.isEmpty()) continue;
                result.add(new TypeUsage(employee.getId(), employee.getEmployeeId(), employee.getLastName() + " " + employee.getFirstName(),
                        type.getId(), type.displayName(), type.getShortName(), type.debitsBalance(),
                        countDays(typeRequests, employee.getLeaveBalanceStartDate()), type.debitsBalance() ? remaining : null));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Balance> getAll() {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("Tenant context is missing");
        List<LeaveType> leaveTypes = types.findByTenantId(tenant);
        Set<Long> annualTypes = leaveTypes.stream()
                .filter(LeaveType::debitsBalance)
                .map(LeaveType::getId).collect(Collectors.toSet());
        Set<Long> unpaidTypes = leaveTypes.stream()
                .filter(t -> "UNPAID".equalsIgnoreCase(t.getLeaveCode()))
                .map(LeaveType::getId).collect(Collectors.toSet());
        List<LeaveRequest> allApproved = requests.findByTenantIdAndStatus(tenant, LeaveRequest.LeaveStatus.APPROVED);
        Map<Long, List<LeaveRequest>> approved = allApproved.stream()
                .filter(r -> r.isLegacyBalanceAccounting() || annualTypes.contains(r.getLeaveTypeId()))
                .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));
        Map<Long, List<LeaveRequest>> unpaid = allApproved.stream()
                .filter(r -> unpaidTypes.contains(r.getLeaveTypeId()))
                .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));
        return employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenant).stream()
                .map(e -> calculate(e, approved.getOrDefault(e.getId(), List.of()),
                        unpaid.getOrDefault(e.getId(), List.of()))).toList();
    }

    static Balance calculate(Employee employee, List<LeaveRequest> requests) {
        return calculate(employee, requests, List.of());
    }

    static Balance calculate(Employee employee, List<LeaveRequest> paid, List<LeaveRequest> unpaid) {
        int used = countChargedDays(paid, employee.getLeaveBalanceStartDate());
        int unpaidDays = countDays(unpaid, employee.getLeaveBalanceStartDate());
        Integer opening = employee.openingLeaveBalance();
        return new Balance(employee.getId(), employee.getEmployeeId(),
                employee.getLastName() + " " + employee.getFirstName(), employee.getLeaveBalanceStartDate(),
                opening, used, opening == null ? null : opening - used, unpaidDays);
    }

    static int countChargedDays(List<LeaveRequest> requests, LocalDate openingDate) {
        // Original main charged explicit amounts, including nonannual types and old dates.
        // Retain those exact amounts; do not retroactively charge historically free requests.
        int legacy = requests.stream().filter(LeaveRequest::isLegacyBalanceAccounting)
                .filter(r -> r.getStatus() == LeaveRequest.LeaveStatus.APPROVED)
                .mapToInt(r -> r.getBalanceChargedDays() == null ? 0 : r.getBalanceChargedDays())
                .reduce(0, Math::addExact);
        return Math.addExact(legacy, countDays(requests.stream()
                .filter(r -> !r.isLegacyBalanceAccounting()).toList(), openingDate));
    }

    static int countDays(List<LeaveRequest> requests, LocalDate openingDate) {
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
        return used;
    }
}
