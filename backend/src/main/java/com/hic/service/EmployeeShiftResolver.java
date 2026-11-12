package com.hic.service;

import com.hic.model.AttendanceLog;
import com.hic.model.Employee;
import com.hic.model.EmployeeShiftAssignment;
import com.hic.model.Timetable;
import com.hic.repository.EmployeeShiftAssignmentRepository;
import com.hic.repository.TimetableRepository;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the work schedule (timetable / shift type) that was effective for an employee
 * on a given calendar date — so schedule changes do not rewrite historical attendance.
 */
@Service
@RequiredArgsConstructor
public class EmployeeShiftResolver {

    private final EmployeeShiftAssignmentRepository assignmentRepository;
    private final TimetableRepository timetableRepository;

    public record ResolvedShift(Long timetableId, String shiftType, boolean knownFromHistory) {
        public ResolvedShift(Long timetableId, String shiftType) {
            this(timetableId, shiftType, false);
        }
    }

    /**
     * Prefers ACTIVE shift assignment covering {@code date}; falls back to the employee's
     * current timetable / shiftType when no history row exists.
     */
    public ResolvedShift resolve(Employee employee, LocalDate date) {
        if (employee == null) {
            return new ResolvedShift(null, null, false);
        }
        Long tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : employee.getTenantId();
        if (tenantId != null && employee.getId() != null && date != null) {
            return assignmentRepository.findActiveByEmployeeAndDate(tenantId, employee.getId(), date)
                    .map(a -> fromAssignment(a, employee, true))
                    .orElseGet(() -> fromEmployeeSnapshot(employee, null, false));
        }
        return fromEmployeeSnapshot(employee, null, false);
    }

    /**
     * Loads all ACTIVE assignments overlapping {@code [start, end]} for the given employees
     * so reports can resolve as-of without N+1 queries.
     */
    public Map<Long, List<EmployeeShiftAssignment>> loadAssignmentsByEmployee(
            Long tenantId,
            Set<Long> employeeIds,
            LocalDate start,
            LocalDate end
    ) {
        if (tenantId == null || employeeIds == null || employeeIds.isEmpty() || start == null || end == null) {
            return Map.of();
        }
        List<EmployeeShiftAssignment> rows = assignmentRepository.findActiveForEmployeesInDateRange(
                tenantId, employeeIds, start, end);
        Map<Long, List<EmployeeShiftAssignment>> byEmployee = new HashMap<>();
        for (EmployeeShiftAssignment row : rows) {
            byEmployee.computeIfAbsent(row.getEmployeeId(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<EmployeeShiftAssignment> list : byEmployee.values()) {
            list.sort(Comparator.comparing(EmployeeShiftAssignment::getEffectiveStartDate));
        }
        return byEmployee;
    }

    public ResolvedShift resolveFromCache(
            Employee employee,
            LocalDate date,
            Map<Long, List<EmployeeShiftAssignment>> assignmentsByEmployee,
            Map<Long, Timetable> timetableMap
    ) {
        if (employee == null) {
            return new ResolvedShift(null, null, false);
        }
        if (date != null && assignmentsByEmployee != null) {
            List<EmployeeShiftAssignment> assignments = assignmentsByEmployee.getOrDefault(employee.getId(), List.of());
            for (EmployeeShiftAssignment assignment : assignments) {
                if (covers(assignment, date)) {
                    Timetable timetable = timetableMap != null
                            ? timetableMap.get(assignment.getTimetableId())
                            : null;
                    String shiftType = timetable != null && timetable.getShiftType() != null && !timetable.getShiftType().isBlank()
                            ? timetable.getShiftType()
                            : employee.getShiftType();
                    return new ResolvedShift(assignment.getTimetableId(), shiftType, true);
                }
            }
        }
        return fromEmployeeSnapshot(employee, timetableMap, false);
    }

    /**
     * Prefer stamp on the log itself, then assignment history. Snapshot fallback is unmarked
     * so reports do not retroactively collapse old punches after a schedule change.
     */
    public ResolvedShift resolveForLog(
            Employee employee,
            AttendanceLog log,
            Map<Long, List<EmployeeShiftAssignment>> assignmentsByEmployee,
            Map<Long, Timetable> timetableMap
    ) {
        if (log != null && log.getShiftType() != null && !log.getShiftType().isBlank()) {
            return new ResolvedShift(log.getTimetableId(), log.getShiftType(), true);
        }
        LocalDate asOf = log == null ? null : AttendanceDayAllocation.workDate(log);
        if (asOf != null && assignmentsByEmployee != null && employee != null) {
            List<EmployeeShiftAssignment> assignments = assignmentsByEmployee.getOrDefault(employee.getId(), List.of());
            for (EmployeeShiftAssignment assignment : assignments) {
                if (covers(assignment, asOf)) {
                    Timetable timetable = timetableMap != null
                            ? timetableMap.get(assignment.getTimetableId())
                            : null;
                    String shiftType = timetable != null && timetable.getShiftType() != null && !timetable.getShiftType().isBlank()
                            ? timetable.getShiftType()
                            : employee.getShiftType();
                    return new ResolvedShift(assignment.getTimetableId(), shiftType, true);
                }
            }
        }
        ResolvedShift snapshot = fromEmployeeSnapshot(employee, timetableMap, false);
        return new ResolvedShift(snapshot.timetableId(), snapshot.shiftType(), false);
    }

    public boolean covers(EmployeeShiftAssignment assignment, LocalDate date) {
        if (assignment == null || date == null || assignment.getEffectiveStartDate() == null) {
            return false;
        }
        if (date.isBefore(assignment.getEffectiveStartDate())) {
            return false;
        }
        return assignment.getEffectiveEndDate() == null || !date.isAfter(assignment.getEffectiveEndDate());
    }

    private ResolvedShift fromAssignment(EmployeeShiftAssignment assignment, Employee employee, boolean known) {
        Timetable timetable = timetableRepository.findById(assignment.getTimetableId()).orElse(null);
        String shiftType = timetable != null && timetable.getShiftType() != null && !timetable.getShiftType().isBlank()
                ? timetable.getShiftType()
                : employee.getShiftType();
        return new ResolvedShift(assignment.getTimetableId(), shiftType, known);
    }

    private ResolvedShift fromEmployeeSnapshot(Employee employee, Map<Long, Timetable> timetableMap, boolean known) {
        if (employee == null) {
            return new ResolvedShift(null, null, known);
        }
        if (employee.getTimetableId() != null) {
            Timetable timetable = timetableMap != null
                    ? timetableMap.get(employee.getTimetableId())
                    : null;
            if (timetable == null) {
                timetable = timetableRepository.findById(employee.getTimetableId()).orElse(null);
            }
            if (timetable != null && timetable.getShiftType() != null && !timetable.getShiftType().isBlank()) {
                return new ResolvedShift(timetable.getId(), timetable.getShiftType(), known);
            }
        }
        return new ResolvedShift(employee.getTimetableId(), employee.getShiftType(), known);
    }

    /** Collect timetable ids referenced by assignments and employee snapshots. */
    public Set<Long> collectTimetableIds(Iterable<Employee> employees, Map<Long, List<EmployeeShiftAssignment>> assignmentsByEmployee) {
        java.util.HashSet<Long> ids = new java.util.HashSet<>();
        if (employees != null) {
            for (Employee employee : employees) {
                if (employee.getTimetableId() != null) {
                    ids.add(employee.getTimetableId());
                }
            }
        }
        if (assignmentsByEmployee != null) {
            for (List<EmployeeShiftAssignment> list : assignmentsByEmployee.values()) {
                for (EmployeeShiftAssignment assignment : list) {
                    if (assignment.getTimetableId() != null) {
                        ids.add(assignment.getTimetableId());
                    }
                }
            }
        }
        ids.removeIf(Objects::isNull);
        return ids;
    }
}
