package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.model.Branch;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.model.EmployeeArea;
import com.hic.model.EmployeeDeviceAccess;
import com.hic.model.EmployeeDeviceAccess.AssignmentSource;
import com.hic.repository.BranchRepository;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeAreaRepository;
import com.hic.repository.EmployeeDeviceAccessRepository;
import com.hic.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeAreaAssignmentService {

    private final EmployeeAreaRepository employeeAreaRepository;
    private final EmployeeDeviceAccessRepository employeeDeviceAccessRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;

    public record AssignmentChange(List<Long> deviceIds, List<Long> removedDeviceIds) {
    }

    public record AreaBatch(
            Map<Long, List<Long>> areaIdsByEmployee,
            Map<Long, List<String>> areaNamesByEmployee) {
    }

    public List<Long> normalizeAndValidateAreaIds(
            List<Long> requestedAreaIds,
            Long primaryAreaId,
            Long tenantId) {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (primaryAreaId != null && primaryAreaId > 0) {
            normalized.add(primaryAreaId);
        }
        if (requestedAreaIds != null) {
            requestedAreaIds.stream()
                    .filter(id -> id != null && id > 0)
                    .forEach(normalized::add);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }

        Map<Long, Branch> branches = branchRepository.findAllById(normalized).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        List<Long> invalidIds = normalized.stream()
                .filter(id -> {
                    Branch branch = branches.get(id);
                    return branch == null
                            || (tenantId != null && branch.getTenantId() != null
                            && !tenantId.equals(branch.getTenantId()));
                })
                .toList();
        if (!invalidIds.isEmpty()) {
            throw new BadRequestException("Invalid or unauthorized area ids: " + invalidIds);
        }
        return List.copyOf(normalized);
    }

    @Transactional
    public AssignmentChange replaceEmployeeAreas(
            Employee employee,
            List<Long> requestedAreaIds,
            Long requestedPrimaryAreaId) {
        Long tenantId = employee.getTenantId();
        List<Long> areaIds = normalizeAndValidateAreaIds(requestedAreaIds, requestedPrimaryAreaId, tenantId);
        Long primaryAreaId = resolvePrimaryAreaId(areaIds, requestedPrimaryAreaId);

        Set<Long> previousDeviceIds = currentDeviceIds(employee.getId());

        employeeAreaRepository.deleteByEmployeeId(employee.getId());
        employeeAreaRepository.flush();
        if (!areaIds.isEmpty()) {
            List<EmployeeArea> memberships = areaIds.stream()
                    .map(areaId -> newMembership(employee, areaId, areaId.equals(primaryAreaId)))
                    .toList();
            employeeAreaRepository.saveAll(memberships);
            employeeAreaRepository.flush();
        }

        reconcileEmployeeAreaAccess(employee, areaIds);
        Set<Long> currentDeviceIds = currentDeviceIds(employee.getId());
        List<Long> removedDeviceIds = previousDeviceIds.stream()
                .filter(id -> !currentDeviceIds.contains(id))
                .sorted()
                .toList();
        return new AssignmentChange(sorted(currentDeviceIds), removedDeviceIds);
    }

    @Transactional
    public boolean ensureAreaMembership(Employee employee, Long areaId) {
        if (employee == null || employee.getId() == null || areaId == null) {
            return false;
        }
        if (employeeAreaRepository.existsByEmployeeIdAndBranchId(employee.getId(), areaId)) {
            return false;
        }

        normalizeAndValidateAreaIds(List.of(areaId), null, employee.getTenantId());
        boolean primary = employeeAreaRepository.findByEmployeeIdOrderByPrimaryDescBranchIdAsc(employee.getId()).isEmpty();
        employeeAreaRepository.save(newMembership(employee, areaId, primary));
        return true;
    }

    @Transactional
    public int linkAreaDeviceAccess(Employee employee, Collection<Long> deviceConfigIds, Long sourceAreaId) {
        if (employee == null || employee.getId() == null || deviceConfigIds == null || deviceConfigIds.isEmpty()) {
            return 0;
        }
        List<Long> normalized = deviceConfigIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        Map<Long, DeviceConfig> devices = deviceConfigRepository.findAllById(normalized).stream()
                .collect(Collectors.toMap(DeviceConfig::getId, Function.identity()));
        List<Long> invalid = normalized.stream()
                .filter(id -> {
                    DeviceConfig device = devices.get(id);
                    return device == null
                            || (sourceAreaId != null && !sourceAreaId.equals(device.getBranchId()))
                            || !sameTenant(employee.getTenantId(), device.getTenantId());
                })
                .toList();
        if (!invalid.isEmpty()) {
            throw new BadRequestException("Invalid devices for area " + sourceAreaId + ": " + invalid);
        }

        int linked = 0;
        for (Long deviceId : normalized) {
            if (employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(employee.getId(), deviceId)) {
                continue;
            }
            employeeDeviceAccessRepository.save(newAreaAccess(employee, deviceId, sourceAreaId));
            linked++;
        }
        return linked;
    }

    @Transactional
    public List<Long> reconcileDeviceAreaAccess(DeviceConfig device) {
        if (device == null || device.getId() == null || device.isAnpr()) {
            return List.of();
        }

        Set<Long> expectedEmployeeIds = new LinkedHashSet<>();
        if (device.getBranchId() != null) {
            List<Long> membershipEmployeeIds = employeeAreaRepository.findByBranchId(device.getBranchId()).stream()
                    .filter(area -> sameTenant(device.getTenantId(), area.getTenantId()))
                    .map(EmployeeArea::getEmployeeId)
                    .distinct()
                    .toList();
            employeeRepository.findAllById(membershipEmployeeIds).stream()
                    .filter(employee -> sameTenant(device.getTenantId(), employee.getTenantId()))
                    .map(Employee::getId)
                    .forEach(expectedEmployeeIds::add);
        }

        List<EmployeeDeviceAccess> existing = employeeDeviceAccessRepository.findByDeviceConfigId(device.getId());
        Map<Long, EmployeeDeviceAccess> byEmployee = existing.stream()
                .collect(Collectors.toMap(
                        EmployeeDeviceAccess::getEmployeeId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<EmployeeDeviceAccess> staleAreaRows = existing.stream()
                .filter(this::isAreaSource)
                .filter(access -> !expectedEmployeeIds.contains(access.getEmployeeId())
                        || !java.util.Objects.equals(device.getBranchId(), access.getSourceBranchId()))
                .toList();
        if (!staleAreaRows.isEmpty()) {
            employeeDeviceAccessRepository.deleteAll(staleAreaRows);
            staleAreaRows.forEach(row -> byEmployee.remove(row.getEmployeeId()));
        }

        Map<Long, Employee> employees = employeeRepository.findAllById(expectedEmployeeIds).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        for (Long employeeId : expectedEmployeeIds) {
            if (byEmployee.containsKey(employeeId)) {
                continue;
            }
            Employee employee = employees.get(employeeId);
            if (employee != null) {
                EmployeeDeviceAccess access = newAreaAccess(employee, device.getId(), device.getBranchId());
                employeeDeviceAccessRepository.save(access);
                byEmployee.put(employeeId, access);
            }
        }
        employeeDeviceAccessRepository.flush();
        return byEmployee.keySet().stream().sorted().toList();
    }

    public List<Long> getAreaIds(Long employeeId) {
        if (employeeId == null) {
            return List.of();
        }
        return employeeAreaRepository.findByEmployeeIdOrderByPrimaryDescBranchIdAsc(employeeId).stream()
                .map(EmployeeArea::getBranchId)
                .distinct()
                .toList();
    }

    public AreaBatch loadAreaBatch(Collection<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return new AreaBatch(Map.of(), Map.of());
        }
        List<EmployeeArea> memberships = employeeAreaRepository.findByEmployeeIdIn(employeeIds);
        Set<Long> branchIds = memberships.stream()
                .map(EmployeeArea::getBranchId)
                .collect(Collectors.toSet());
        Map<Long, String> branchNames = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));

        Comparator<EmployeeArea> membershipOrder = Comparator
                .comparing(EmployeeArea::isPrimary).reversed()
                .thenComparing(EmployeeArea::getBranchId);
        Map<Long, List<EmployeeArea>> grouped = memberships.stream()
                .sorted(membershipOrder)
                .collect(Collectors.groupingBy(
                        EmployeeArea::getEmployeeId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, List<Long>> areaIdsByEmployee = new LinkedHashMap<>();
        Map<Long, List<String>> areaNamesByEmployee = new LinkedHashMap<>();
        grouped.forEach((employeeId, rows) -> {
            areaIdsByEmployee.put(employeeId, rows.stream().map(EmployeeArea::getBranchId).distinct().toList());
            areaNamesByEmployee.put(employeeId, rows.stream()
                    .map(EmployeeArea::getBranchId)
                    .map(branchNames::get)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList());
        });
        return new AreaBatch(areaIdsByEmployee, areaNamesByEmployee);
    }

    public List<Long> resolveDeviceIdsForAreas(Collection<Long> areaIds, Long tenantId) {
        if (areaIds == null || areaIds.isEmpty()) {
            return List.of();
        }
        Set<Long> normalized = areaIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<DeviceConfig> devices = tenantId != null
                ? deviceConfigRepository.findByTenantId(tenantId)
                : deviceConfigRepository.findAll();
        return devices.stream()
                .filter(device -> normalized.contains(device.getBranchId()))
                .map(DeviceConfig::getId)
                .distinct()
                .sorted()
                .toList();
    }

    private void reconcileEmployeeAreaAccess(Employee employee, List<Long> areaIds) {
        Map<Long, Long> expectedDeviceArea = new LinkedHashMap<>();
        for (Long areaId : areaIds) {
            List<DeviceConfig> devices = employee.getTenantId() != null
                    ? deviceConfigRepository.findByTenantIdAndBranchId(employee.getTenantId(), areaId)
                    : deviceConfigRepository.findByBranchId(areaId);
            devices.stream()
                    .filter(device -> !device.isAnpr())
                    .filter(device -> sameTenant(employee.getTenantId(), device.getTenantId()))
                    .forEach(device -> expectedDeviceArea.put(device.getId(), areaId));
        }

        List<EmployeeDeviceAccess> existing = employeeDeviceAccessRepository.findByEmployeeId(employee.getId());
        Map<Long, EmployeeDeviceAccess> byDevice = existing.stream()
                .collect(Collectors.toMap(
                        EmployeeDeviceAccess::getDeviceConfigId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<EmployeeDeviceAccess> stale = existing.stream()
                .filter(this::isAreaSource)
                .filter(access -> !java.util.Objects.equals(
                        expectedDeviceArea.get(access.getDeviceConfigId()),
                        access.getSourceBranchId()))
                .toList();
        if (!stale.isEmpty()) {
            employeeDeviceAccessRepository.deleteAll(stale);
            stale.forEach(access -> byDevice.remove(access.getDeviceConfigId()));
        }

        for (Map.Entry<Long, Long> expected : expectedDeviceArea.entrySet()) {
            if (byDevice.containsKey(expected.getKey())) {
                continue;
            }
            EmployeeDeviceAccess access = newAreaAccess(employee, expected.getKey(), expected.getValue());
            employeeDeviceAccessRepository.save(access);
            byDevice.put(expected.getKey(), access);
        }
        employeeDeviceAccessRepository.flush();
    }

    private EmployeeArea newMembership(Employee employee, Long areaId, boolean primary) {
        EmployeeArea membership = new EmployeeArea();
        membership.setTenantId(employee.getTenantId());
        membership.setEmployeeId(employee.getId());
        membership.setBranchId(areaId);
        membership.setPrimary(primary);
        return membership;
    }

    private EmployeeDeviceAccess newAreaAccess(Employee employee, Long deviceId, Long sourceAreaId) {
        EmployeeDeviceAccess access = new EmployeeDeviceAccess();
        access.setTenantId(employee.getTenantId());
        access.setEmployeeId(employee.getId());
        access.setDeviceConfigId(deviceId);
        access.setAssignmentSource(AssignmentSource.AREA);
        access.setSourceBranchId(sourceAreaId);
        return access;
    }

    private Long resolvePrimaryAreaId(List<Long> areaIds, Long requestedPrimaryAreaId) {
        if (requestedPrimaryAreaId != null && areaIds.contains(requestedPrimaryAreaId)) {
            return requestedPrimaryAreaId;
        }
        return areaIds.isEmpty() ? null : areaIds.get(0);
    }

    private Set<Long> currentDeviceIds(Long employeeId) {
        return employeeDeviceAccessRepository.findByEmployeeId(employeeId).stream()
                .map(EmployeeDeviceAccess::getDeviceConfigId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Long> sorted(Collection<Long> values) {
        return values.stream().distinct().sorted().toList();
    }

    private boolean isAreaSource(EmployeeDeviceAccess access) {
        return AssignmentSource.AREA.equals(access.getAssignmentSource());
    }

    private boolean sameTenant(Long expected, Long actual) {
        return expected == null || actual == null || expected.equals(actual);
    }
}
