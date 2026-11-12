package com.hic.service;

import com.hic.dto.DeviceEmployeeAssignmentDTO.AssignmentView;
import com.hic.dto.DeviceEmployeeAssignmentDTO.EmployeeOption;
import com.hic.dto.DeviceEmployeeAssignmentDTO.EmployeeSyncResult;
import com.hic.dto.DeviceEmployeeAssignmentDTO.SyncResult;
import com.hic.dto.DeviceEmployeeAssignmentDTO.UpdateRequest;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
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
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
public class DeviceEmployeeAssignmentService {

    private final DeviceConfigRepository deviceConfigRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeAreaRepository employeeAreaRepository;
    private final EmployeeDeviceAccessRepository employeeDeviceAccessRepository;
    private final BranchRepository branchRepository;
    private final EmployeeAreaAssignmentService employeeAreaAssignmentService;
    private final IsapiEmployeeUserSyncService isapiEmployeeUserSyncService;
    private final EmployeeFaceDeviceSyncService employeeFaceDeviceSyncService;

    @Transactional(readOnly = true)
    public AssignmentView getAssignments(Long deviceConfigId) {
        DeviceConfig device = requireDevice(deviceConfigId);
        Long tenantId = effectiveTenantId(device);
        List<Employee> employees = loadEmployees(tenantId);
        List<Long> employeeIds = employees.stream().map(Employee::getId).toList();

        EmployeeAreaAssignmentService.AreaBatch areaBatch =
                employeeAreaAssignmentService.loadAreaBatch(employeeIds);
        Map<Long, EmployeeDeviceAccess> accessByEmployee = employeeDeviceAccessRepository
                .findByDeviceConfigId(deviceConfigId).stream()
                .collect(Collectors.toMap(
                        EmployeeDeviceAccess::getEmployeeId,
                        Function.identity(),
                        (left, right) -> left));

        List<EmployeeOption> options = employees.stream()
                .map(employee -> toOption(employee, device, areaBatch, accessByEmployee.get(employee.getId())))
                .toList();
        String areaName = device.getBranchId() == null
                ? null
                : branchRepository.findById(device.getBranchId()).map(Branch::getName).orElse(null);
        return new AssignmentView(
                device.getId(),
                device.getDeviceName(),
                device.getBranchId(),
                areaName,
                options);
    }

    @Transactional
    public AssignmentView updateManualAssignments(Long deviceConfigId, UpdateRequest request) {
        DeviceConfig device = requireDevice(deviceConfigId);
        employeeAreaAssignmentService.reconcileDeviceAreaAccess(device);

        Set<Long> requestedManualIds = request == null || request.getManualEmployeeIds() == null
                ? Set.of()
                : request.getManualEmployeeIds().stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Long tenantId = effectiveTenantId(device);
        Map<Long, Employee> requestedEmployees = employeeRepository.findAllById(requestedManualIds).stream()
                .filter(employee -> sameTenant(tenantId, employee.getTenantId()))
                .filter(employee -> !Employee.EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus()))
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<Long> invalidIds = requestedManualIds.stream()
                .filter(id -> !requestedEmployees.containsKey(id))
                .toList();
        if (!invalidIds.isEmpty()) {
            throw new BadRequestException("Invalid or unauthorized employee ids: " + invalidIds);
        }

        Set<Long> nativeAreaEmployeeIds = device.getBranchId() == null
                ? Set.of()
                : employeeAreaRepository.findByBranchId(device.getBranchId()).stream()
                .filter(area -> sameTenant(tenantId, area.getTenantId()))
                .map(EmployeeArea::getEmployeeId)
                .collect(Collectors.toSet());

        List<EmployeeDeviceAccess> existingRows = employeeDeviceAccessRepository.findByDeviceConfigId(deviceConfigId);
        Map<Long, EmployeeDeviceAccess> existingByEmployee = existingRows.stream()
                .collect(Collectors.toMap(
                        EmployeeDeviceAccess::getEmployeeId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<EmployeeDeviceAccess> toDelete = new ArrayList<>();
        for (EmployeeDeviceAccess access : existingRows) {
            Long employeeId = access.getEmployeeId();
            if (nativeAreaEmployeeIds.contains(employeeId)) {
                if (AssignmentSource.AREA.equals(access.getAssignmentSource())) {
                    access.setSourceBranchId(device.getBranchId());
                    employeeDeviceAccessRepository.save(access);
                }
                continue;
            }
            if (!requestedManualIds.contains(employeeId)
                    && !AssignmentSource.AREA.equals(access.getAssignmentSource())) {
                toDelete.add(access);
                existingByEmployee.remove(employeeId);
            }
        }
        if (!toDelete.isEmpty()) {
            employeeDeviceAccessRepository.deleteAll(toDelete);
        }

        for (Long employeeId : requestedManualIds) {
            if (nativeAreaEmployeeIds.contains(employeeId)) {
                continue;
            }
            EmployeeDeviceAccess existing = existingByEmployee.get(employeeId);
            if (existing != null) {
                existing.setAssignmentSource(AssignmentSource.MANUAL);
                existing.setSourceBranchId(null);
                employeeDeviceAccessRepository.save(existing);
                continue;
            }
            Employee employee = requestedEmployees.get(employeeId);
            EmployeeDeviceAccess access = new EmployeeDeviceAccess();
            access.setTenantId(employee.getTenantId());
            access.setEmployeeId(employeeId);
            access.setDeviceConfigId(deviceConfigId);
            access.setAssignmentSource(AssignmentSource.MANUAL);
            access.setSourceBranchId(null);
            employeeDeviceAccessRepository.save(access);
        }
        employeeDeviceAccessRepository.flush();
        return getAssignments(deviceConfigId);
    }

    @Transactional
    public SyncResult syncEmployees(Long deviceConfigId) {
        DeviceConfig device = requireDevice(deviceConfigId);
        List<Long> assignedEmployeeIds = employeeAreaAssignmentService.reconcileDeviceAreaAccess(device);
        Long tenantId = effectiveTenantId(device);
        List<Employee> employees = employeeRepository.findAllById(assignedEmployeeIds).stream()
                .filter(employee -> sameTenant(tenantId, employee.getTenantId()))
                .filter(employee -> Employee.EmploymentStatus.ACTIVE.equals(employee.getEmploymentStatus()))
                .sorted(Comparator.comparing(Employee::getFirstName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(Employee::getLastName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        Long isapiDeviceId = parseIsapiDeviceId(device);
        SyncResult result = new SyncResult(deviceConfigId, employees.size(), 0, 0, 0, 0, 0, new ArrayList<>());
        for (Employee employee : employees) {
            try {
                isapiEmployeeUserSyncService.syncEmployee(employee, List.of(isapiDeviceId));
                result.setSucceeded(result.getSucceeded() + 1);
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add(employeeLabel(employee) + ": " + safeMessage(ex));
                continue;
            }

            try {
                EmployeeFaceDeviceSyncService.SyncOutcome faceOutcome =
                        employeeFaceDeviceSyncService.syncIfAvailable(employee, isapiDeviceId);
                if (EmployeeFaceDeviceSyncService.SyncOutcome.SYNCED.equals(faceOutcome)) {
                    result.setFacesSynced(result.getFacesSynced() + 1);
                } else {
                    result.setFacesSkipped(result.getFacesSkipped() + 1);
                }
            } catch (RuntimeException ex) {
                result.setFacesFailed(result.getFacesFailed() + 1);
                result.getErrors().add(employeeLabel(employee) + " (şəkil): " + safeMessage(ex));
            }
        }
        return result;
    }

    public EmployeeSyncResult syncEmployee(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
        Long tenantId = TenantContext.getTenantId();
        if (!sameTenant(tenantId, employee.getTenantId())) {
            throw new ResourceNotFoundException("Employee", employeeId);
        }
        if (Employee.EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus())) {
            throw new BadRequestException("İşdən çıxarılmış əməkdaş cihazlara yenidən göndərilə bilməz");
        }

        Set<Long> targetDeviceConfigIds = employeeDeviceAccessRepository.findByEmployeeId(employeeId).stream()
                .map(EmployeeDeviceAccess::getDeviceConfigId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> areaIds = employeeAreaAssignmentService.getAreaIds(employeeId);
        targetDeviceConfigIds.addAll(employeeAreaAssignmentService.resolveDeviceIdsForAreas(
                areaIds,
                employee.getTenantId()));

        List<DeviceConfig> devices = deviceConfigRepository.findAllById(targetDeviceConfigIds).stream()
                .filter(device -> sameTenant(employee.getTenantId(), device.getTenantId()))
                .sorted(Comparator.comparing(DeviceConfig::getId))
                .toList();
        EmployeeSyncResult result = new EmployeeSyncResult(
                employeeId,
                devices.size(),
                0,
                0,
                0,
                0,
                new ArrayList<>());

        for (DeviceConfig device : devices) {
            Long isapiDeviceId;
            try {
                isapiDeviceId = parseIsapiDeviceId(device);
                isapiEmployeeUserSyncService.syncEmployee(employee, List.of(isapiDeviceId));
                result.setUsersSynced(result.getUsersSynced() + 1);
            } catch (RuntimeException ex) {
                addEmployeeSyncFailure(result, device, ex);
                continue;
            }

            try {
                EmployeeFaceDeviceSyncService.SyncOutcome faceOutcome =
                        employeeFaceDeviceSyncService.syncIfAvailable(employee, isapiDeviceId);
                if (EmployeeFaceDeviceSyncService.SyncOutcome.SYNCED.equals(faceOutcome)
                        || EmployeeFaceDeviceSyncService.SyncOutcome.ALREADY_PRESENT.equals(faceOutcome)) {
                    result.setFacesSynced(result.getFacesSynced() + 1);
                } else if (EmployeeFaceDeviceSyncService.SyncOutcome.NO_FACE.equals(faceOutcome)) {
                    result.setFacesSkipped(result.getFacesSkipped() + 1);
                } else {
                    result.setFailedDevices(result.getFailedDevices() + 1);
                    result.getErrors().add(deviceLabel(device) + ": ISAPI üz sinxronizasiyası konfiqurasiya edilməyib");
                }
            } catch (RuntimeException ex) {
                addEmployeeSyncFailure(result, device, ex);
            }
        }
        return result;
    }

    private EmployeeOption toOption(
            Employee employee,
            DeviceConfig device,
            EmployeeAreaAssignmentService.AreaBatch areaBatch,
            EmployeeDeviceAccess access) {
        List<Long> areaIds = areaBatch.areaIdsByEmployee().getOrDefault(employee.getId(), List.of());
        boolean areaAssigned = device.getBranchId() != null && areaIds.contains(device.getBranchId());
        boolean manuallyAssigned = access != null && !AssignmentSource.AREA.equals(access.getAssignmentSource());
        String fullName = ((employee.getFirstName() == null ? "" : employee.getFirstName())
                + " " + (employee.getLastName() == null ? "" : employee.getLastName())).trim();
        return new EmployeeOption(
                employee.getId(),
                employee.getEmployeeId(),
                fullName,
                employee.getFinNumber(),
                areaIds,
                areaBatch.areaNamesByEmployee().getOrDefault(employee.getId(), List.of()),
                areaAssigned,
                manuallyAssigned,
                areaAssigned || access != null);
    }

    private DeviceConfig requireDevice(Long deviceConfigId) {
        DeviceConfig device = deviceConfigRepository.findById(deviceConfigId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", deviceConfigId));
        Long tenantId = TenantContext.getTenantId();
        if (!sameTenant(tenantId, device.getTenantId())) {
            throw new BadRequestException("Device does not belong to your tenant");
        }
        if (device.isAnpr()) throw new BadRequestException("ANPR kamera əməkdaş sinxronunu dəstəkləmir.");
        return device;
    }

    private List<Employee> loadEmployees(Long tenantId) {
        if (tenantId != null) {
            return employeeRepository.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenantId).stream()
                    .filter(employee -> !Employee.EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus()))
                    .toList();
        }
        return employeeRepository.findAll().stream()
                .filter(employee -> !Employee.EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus()))
                .sorted(Comparator.comparing(Employee::getFirstName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(Employee::getLastName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private Long effectiveTenantId(DeviceConfig device) {
        return device.getTenantId() != null ? device.getTenantId() : TenantContext.getTenantId();
    }

    private Long parseIsapiDeviceId(DeviceConfig device) {
        try {
            return Long.valueOf(device.getDeviceId());
        } catch (RuntimeException ex) {
            throw new BadRequestException("Invalid ISAPI device id for device " + device.getId());
        }
    }

    private String employeeLabel(Employee employee) {
        return employee.getEmployeeId() + " — "
                + ((employee.getFirstName() == null ? "" : employee.getFirstName()) + " "
                + (employee.getLastName() == null ? "" : employee.getLastName())).trim();
    }

    private void addEmployeeSyncFailure(EmployeeSyncResult result, DeviceConfig device, RuntimeException ex) {
        result.setFailedDevices(result.getFailedDevices() + 1);
        result.getErrors().add(deviceLabel(device) + ": " + safeMessage(ex));
    }

    private String deviceLabel(DeviceConfig device) {
        if (device.getDeviceName() != null && !device.getDeviceName().isBlank()) {
            return device.getDeviceName();
        }
        return "Cihaz " + device.getId();
    }

    private String safeMessage(RuntimeException ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private boolean sameTenant(Long expected, Long actual) {
        return expected == null || actual == null || expected.equals(actual);
    }
}
