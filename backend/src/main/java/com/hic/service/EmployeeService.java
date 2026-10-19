package com.hic.service;

import com.hic.dto.EmployeeDTO;
import com.hic.dto.EmployeeTerminationResultDTO;
import com.hic.dto.EmployeeSearchResultDTO;
import com.hic.dto.EmployeeResponseDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.Department;
import com.hic.model.DeviceConfig;
import com.hic.model.Door;
import com.hic.model.Employee;
import com.hic.model.Employee.EmploymentStatus;
import com.hic.model.EmployeeDeviceAccess;
import com.hic.model.Position;
import com.hic.model.Timetable;
import com.hic.repository.DepartmentRepository;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.DoorRepository;
import com.hic.repository.EmployeeDeviceAccessRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.PositionRepository;
import com.hic.repository.TenantRepository;
import com.hic.repository.TimetableRepository;
import com.hic.util.AppTimeZone;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final TimetableRepository timetableRepository;
    private final EmployeeFaceImageService employeeFaceImageService;
    private final DeviceConfigRepository deviceConfigRepository;
    private final DoorRepository doorRepository;
    private final EmployeeDeviceAccessRepository employeeDeviceAccessRepository;
    private final IsapiEmployeeUserSyncService isapiEmployeeUserSyncService;
    private final EmployeeDeviceRemovalService employeeDeviceRemovalService;
    private final com.hic.repository.EmployeeDeviceRemovalJobRepository removalJobRepository;
    private final UserScopeService userScopeService;
    private final TenantRepository tenantRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final EmployeeAreaAssignmentService employeeAreaAssignmentService;

    public PaginatedResponse<EmployeeResponseDTO> getAll(int page, int size, String sortBy) {
        return getAll(page, size, sortBy, null);
    }

    public PaginatedResponse<EmployeeResponseDTO> getAll(int page, int size, String sortBy, Long requestedBranchId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy != null ? sortBy : "id"));
        Long tenantId = TenantContext.getTenantId();
        Long effectiveBranchId = userScopeService.resolveBranchScope(requestedBranchId);
        Page<Employee> employeePage;
        if (tenantId != null && effectiveBranchId != null) {
            employeePage = employeeRepository.findByTenantIdAndAreaIdAndEmploymentStatusNot(
                    tenantId, effectiveBranchId, EmploymentStatus.TERMINATED, pageable);
        } else if (tenantId != null) {
            employeePage = employeeRepository.findByTenantIdAndEmploymentStatusNot(
                    tenantId, EmploymentStatus.TERMINATED, pageable);
        } else {
            employeePage = employeeRepository.findByEmploymentStatusNot(
                    EmploymentStatus.TERMINATED, pageable);
        }
        return buildPaginatedResponse(employeePage);
    }

    public EmployeeResponseDTO getById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        Employee employee = tenantId != null
                ? employeeRepository.findByTenantIdAndId(tenantId, id)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", id))
                : employeeRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        return toResponseDTO(employee);
    }

    public PaginatedResponse<EmployeeResponseDTO> getByBranch(Long branchId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Long tenantId = TenantContext.getTenantId();
        Page<Employee> employeePage = tenantId != null
                ? employeeRepository.findByTenantIdAndAreaIdAndEmploymentStatusNot(
                        tenantId, branchId, EmploymentStatus.TERMINATED, pageable)
                : employeeRepository.findByDepartmentIdInAndEmploymentStatusNot(
                        departmentRepository.findByBranchId(branchId).stream()
                                .map(Department::getId)
                                .toList(),
                        EmploymentStatus.TERMINATED,
                        pageable
                );
        return buildPaginatedResponse(employeePage);
    }

    public List<EmployeeResponseDTO> getByDepartment(Long departmentId) {
        Long tenantId = TenantContext.getTenantId();
        List<Employee> employees = tenantId != null
                ? employeeRepository.findByTenantIdAndDepartmentIdAndEmploymentStatusNot(
                        tenantId, departmentId, EmploymentStatus.TERMINATED)
                : employeeRepository.findByDepartmentIdAndEmploymentStatusNot(
                        departmentId, EmploymentStatus.TERMINATED);
        return mapEmployeeListToDTOs(employees);
    }

    public List<EmployeeResponseDTO> getByStatus(EmploymentStatus status) {
        Long tenantId = TenantContext.getTenantId();
        List<Employee> employees = tenantId != null
                ? employeeRepository.findByTenantIdAndEmploymentStatus(tenantId, status)
                : employeeRepository.findByEmploymentStatus(status);
        return mapEmployeeListToDTOs(employees);
    }

    public PaginatedResponse<EmployeeResponseDTO> search(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Long tenantId = TenantContext.getTenantId();
        Page<Employee> employeePage = tenantId != null
                ? employeeRepository.searchByTenantAndEmploymentStatusNot(
                        tenantId, query, EmploymentStatus.TERMINATED, pageable)
                : employeeRepository.searchByEmploymentStatusNot(
                        query, EmploymentStatus.TERMINATED, pageable);
        return buildPaginatedResponse(employeePage);
    }

    public List<EmployeeSearchResultDTO> searchEmployees(String query) {
        String normalizedQuery = query != null ? query.trim() : "";
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }

        Pageable pageable = PageRequest.of(0, 20, Sort.by("firstName").ascending().and(Sort.by("lastName").ascending()));
        Long tenantId = TenantContext.getTenantId();
        Long branchId = userScopeService.resolveBranchScope(null);
        List<Employee> matchedEmployees = tenantId != null
                ? employeeRepository.searchMinimalByTenant(tenantId, branchId, normalizedQuery, pageable)
                : employeeRepository.searchMinimal(branchId, normalizedQuery, pageable);
        List<Employee> employees = matchedEmployees.stream()
                .filter(employee -> !EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus()))
                .toList();

        Map<Long, String> departmentNames = departmentRepository.findAllById(
                        employees.stream()
                                .map(Employee::getDepartmentId)
                                .filter(java.util.Objects::nonNull)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Department::getId, Department::getDepartmentName));

        return employees.stream()
                .map(employee -> {
                    EmployeeSearchResultDTO dto = new EmployeeSearchResultDTO();
                    dto.setEmployeePk(employee.getId());
                    dto.setEmployeeId(employee.getEmployeeId());
                    dto.setFirstName(employee.getFirstName());
                    dto.setLastName(employee.getLastName());
                    dto.setFinNumber(employee.getFinNumber());
                    dto.setDepartmentId(employee.getDepartmentId());
                    dto.setDepartmentName(employee.getDepartmentId() != null
                            ? departmentNames.get(employee.getDepartmentId())
                            : null);
                    dto.setBranchId(employee.getBranchId());
                    dto.setShiftType(employee.getShiftType());
                    return dto;
                })
                .toList();
    }

    @Transactional
    public EmployeeResponseDTO create(EmployeeDTO dto) {
        return toResponseDTO(createEmployee(dto, null, true));
    }

    /**
     * Creates an employee during a validated bulk import. Area/device assignments are stored in
     * the database, but no request is sent to physical terminals. Operators can later use the
     * explicit employee synchronization action for the target area.
     */
    @Transactional
    public void createForImport(EmployeeDTO dto, String requestedEmployeeId) {
        createEmployee(dto, requestedEmployeeId, false);
    }

    private Employee createEmployee(EmployeeDTO dto, String requestedEmployeeId, boolean syncDevices) {
        if (EmploymentStatus.TERMINATED.equals(dto.getEmploymentStatus())) {
            throw new BadRequestException("Yeni əməkdaş işdən çıxarılmış statusunda yaradıla bilməz");
        }
        Long tenantId = TenantContext.getTenantId();
        enforceEmployeeQuota(tenantId);
        List<Long> areaIds = employeeAreaAssignmentService.normalizeAndValidateAreaIds(
                dto.getAreaIds(), dto.getBranchId(), tenantId);
        Long primaryAreaId = resolvePrimaryAreaId(areaIds, dto.getBranchId());
        Timetable timetable = validateWorkReferences(dto, tenantId, primaryAreaId);

        if (dto.getFinNumber() != null && !dto.getFinNumber().isBlank()) {
            if (tenantId != null) {
                employeeRepository.findByTenantIdAndFinNumber(tenantId, dto.getFinNumber()).ifPresent(e -> {
                    throw new BadRequestException(
                            "Bu FIN kodu artıq mövcuddur. Eyni FIN kodu ilə ikinci əməkdaş yaratmaq mümkün deyil.");
                });
            } else {
                employeeRepository.findByFinNumber(dto.getFinNumber()).ifPresent(e -> {
                    throw new BadRequestException(
                            "Bu FIN kodu artıq mövcuddur. Eyni FIN kodu ilə ikinci əməkdaş yaratmaq mümkün deyil.");
                });
            }
        }

        Employee employee = new Employee();
        mapDtoToEmployee(dto, employee);
        applyTimetableShiftType(employee, timetable);
        employee.setBranchId(primaryAreaId);
        if (tenantId != null) {
            employee.setTenantId(tenantId);
        }
        String employeeId = requestedEmployeeId != null && !requestedEmployeeId.isBlank()
                ? requestedEmployeeId.trim()
                : generateEmployeeId(tenantId);
        validateEmployeeIdAvailable(tenantId, employeeId);
        employee.setEmployeeId(employeeId);
        if (employee.getEmploymentStatus() == null) {
            employee.setEmploymentStatus(EmploymentStatus.ACTIVE);
        }

        Employee saved = employeeRepository.save(employee);
        if (saved.getTimetableId() != null) {
            LocalDate effectiveFrom = saved.getHireDate() != null ? saved.getHireDate() : AppTimeZone.today();
            shiftAssignmentService.syncScheduleFromEmployee(saved, null, saved.getTimetableId(), effectiveFrom);
            saved = employeeRepository.save(saved);
        }
        EmployeeAreaAssignmentService.AssignmentChange assignment =
                employeeAreaAssignmentService.replaceEmployeeAreas(saved, areaIds, primaryAreaId);
        if (syncDevices) {
            syncEmployeeToDevicesSafely(saved, assignment.deviceIds());
        }
        return saved;
    }

    @Transactional
    public EmployeeResponseDTO update(Long id, EmployeeDTO dto) {
        Long contextTenantId = TenantContext.getTenantId();
        Employee employee = contextTenantId != null
                ? employeeRepository.findByTenantIdAndId(contextTenantId, id)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", id))
                : employeeRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        if (EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus())) {
            throw new BadRequestException("İşdən çıxarılmış əməkdaş redaktə edilə bilməz");
        }
        if (EmploymentStatus.TERMINATED.equals(dto.getEmploymentStatus())) {
            throw new BadRequestException("İşdən çıxarma əməliyyatı ayrıca funksiya ilə aparılmalıdır");
        }

        Long tenantId = employee.getTenantId() != null ? employee.getTenantId() : contextTenantId;
        List<Long> areaIds = employeeAreaAssignmentService.normalizeAndValidateAreaIds(
                dto.getAreaIds(), dto.getBranchId(), tenantId);
        Long primaryAreaId = resolvePrimaryAreaId(areaIds, dto.getBranchId());
        Timetable timetable = validateWorkReferences(dto, tenantId, primaryAreaId);

        if (dto.getFinNumber() != null && !dto.getFinNumber().isBlank()) {
            if (tenantId != null) {
                employeeRepository.findByTenantIdAndFinNumber(tenantId, dto.getFinNumber()).ifPresent(existing -> {
                    if (!existing.getId().equals(employee.getId())) {
                        throw new BadRequestException(
                                "Bu FIN kodu artıq mövcuddur. Eyni FIN kodu ilə ikinci əməkdaş yaratmaq mümkün deyil.");
                    }
                });
            } else {
                employeeRepository.findByFinNumber(dto.getFinNumber()).ifPresent(existing -> {
                    if (!existing.getId().equals(employee.getId())) {
                        throw new BadRequestException(
                                "Bu FIN kodu artıq mövcuddur. Eyni FIN kodu ilə ikinci əməkdaş yaratmaq mümkün deyil.");
                    }
                });
            }
        }

        Long previousTimetableId = employee.getTimetableId();
        mapDtoToEmployee(dto, employee);
        applyTimetableShiftType(employee, timetable);
        employee.setBranchId(primaryAreaId);
        if (!java.util.Objects.equals(previousTimetableId, employee.getTimetableId())) {
            shiftAssignmentService.syncScheduleFromEmployee(
                    employee, previousTimetableId, employee.getTimetableId(), AppTimeZone.today());
        }

        Employee saved = employeeRepository.save(employee);
        EmployeeAreaAssignmentService.AssignmentChange assignment =
                employeeAreaAssignmentService.replaceEmployeeAreas(saved, areaIds, primaryAreaId);
        deleteEmployeeFromDevicesSafely(saved, assignment.removedDeviceIds());
        syncEmployeeToDevicesSafely(saved, assignment.deviceIds());
        return toResponseDTO(saved);
    }

    public List<String> getEmployeeDoorAccess(Long employeeId) {
        if (employeeId == null) {
            return Collections.emptyList();
        }
        List<Long> deviceIds = getEmployeeDeviceIds(employeeId);
        if (deviceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<DeviceConfig> devices = deviceConfigRepository.findAllById(deviceIds);
        List<Long> doorIds = devices.stream()
                .map(DeviceConfig::getDoorId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (doorIds.isEmpty()) {
            return Collections.emptyList();
        }
        return doorRepository.findAllById(doorIds).stream()
                .map(d -> d.getName() + " (" + d.getStatus() + ")")
                .sorted()
                .toList();
    }

    public EmployeeTerminationResultDTO terminate(Long id) {
        Long contextTenantId = TenantContext.getTenantId();
        Employee employee = contextTenantId != null
                ? employeeRepository.findByTenantIdAndId(contextTenantId, id)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", id))
                : employeeRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        Long tenantId = employee.getTenantId() != null ? employee.getTenantId() : contextTenantId;

        Set<Long> targetDeviceIds = new LinkedHashSet<>(getEmployeeDeviceIds(id));
        List<Long> employeeAreaIds = employeeAreaAssignmentService.getAreaIds(id);
        if (employeeAreaIds.isEmpty() && employee.getBranchId() != null) {
            employeeAreaIds = List.of(employee.getBranchId());
        }
        targetDeviceIds.addAll(employeeAreaAssignmentService.resolveDeviceIdsForAreas(employeeAreaIds, tenantId));
        return employeeDeviceRemovalService.terminate(employee, targetDeviceIds);
    }

    /**
     * Backward-compatible endpoint behavior. Employee history is preserved.
     */
    public void delete(Long id) {
        terminate(id);
    }

    private void mapDtoToEmployee(EmployeeDTO dto, Employee employee) {
        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setBirthDate(dto.getBirthDate());
        employee.setGender(dto.getGender());
        employee.setMobilePhone(dto.getMobilePhone());
        employee.setEmail(dto.getEmail());
        employee.setFinNumber(dto.getFinNumber());
        if (dto.getFaceId() != null) {
            employee.setFaceId(dto.getFaceId());
        }
        if (dto.getCardId() != null) {
            employee.setCardId(dto.getCardId());
        }
        employee.setSerialNumber(dto.getSerialNumber());
        employee.setContractNumber(dto.getContractNumber());
        employee.setBranchId(dto.getBranchId());
        employee.setDepartmentId(dto.getDepartmentId());
        employee.setPositionId(dto.getPositionId());
        employee.setHireDate(dto.getHireDate() != null ? dto.getHireDate() : AppTimeZone.today());
        employee.setContractEndDate(dto.getContractEndDate());
        employee.setAnnualLeaveDuration(dto.getAnnualLeaveDuration());
        employee.setAnnualLeaveBalance(dto.getAnnualLeaveBalance());
        if (dto.getGroupName() != null) {
            employee.setGroupName(dto.getGroupName());
        }
        employee.setSalary(dto.getSalary());
        employee.setHourlyRate(dto.getHourlyRate());
        employee.setAllowance(dto.getAllowance());
        employee.setEmergencyContact(dto.getEmergencyContact());
        employee.setAddress(dto.getAddress());
        employee.setNotes(dto.getNotes());
        if (dto.getEmploymentStatus() != null) {
            employee.setEmploymentStatus(dto.getEmploymentStatus());
        }
        if (dto.getFatherName() != null) employee.setFatherName(dto.getFatherName());
        if (dto.getArea() != null) employee.setArea(dto.getArea());
        if (dto.getShiftType() != null) employee.setShiftType(dto.getShiftType());
        employee.setTimetableId(dto.getTimetableId());
    }

    private EmployeeResponseDTO toResponseDTO(Employee employee) {
        EmployeeAreaAssignmentService.AreaBatch areaBatch =
                employeeAreaAssignmentService.loadAreaBatch(List.of(employee.getId()));
        return toResponseDTO(employee, null, null, null, areaBatch);
    }

    private EmployeeResponseDTO toResponseDTO(Employee employee,
                                               Map<Long, String> departmentNames,
                                               Map<Long, String> positionNames,
                                               Map<Long, List<Long>> employeeDeviceIds,
                                               EmployeeAreaAssignmentService.AreaBatch areaBatch) {
        EmployeeResponseDTO dto = new EmployeeResponseDTO();
        dto.setId(employee.getId());
        dto.setEmployeeId(employee.getEmployeeId());
        dto.setDeviceEmployeeNo(employee.getDeviceEmployeeNo());
        dto.setFirstName(employee.getFirstName());
        dto.setLastName(employee.getLastName());
        dto.setBirthDate(employee.getBirthDate());
        dto.setGender(employee.getGender());
        dto.setMobilePhone(employee.getMobilePhone());
        dto.setEmail(employee.getEmail());
        dto.setFinNumber(employee.getFinNumber());
        dto.setFaceId(employee.getFaceId());
        employeeFaceImageService.getLatestEmployeeFacePublicUrl(employee.getId())
                .ifPresent(dto::setFaceImageUrl);
        dto.setCardId(employee.getCardId());
        dto.setSerialNumber(employee.getSerialNumber());
        dto.setContractNumber(employee.getContractNumber());
        dto.setBranchId(employee.getBranchId());
        List<Long> areaIds = areaBatch.areaIdsByEmployee().getOrDefault(employee.getId(), List.of());
        List<String> areaNames = areaBatch.areaNamesByEmployee().getOrDefault(employee.getId(), List.of());
        if (areaIds.isEmpty() && employee.getBranchId() != null) {
            areaIds = List.of(employee.getBranchId());
        }
        dto.setAreaIds(areaIds);
        dto.setAreaNames(areaNames);
        if (!areaNames.isEmpty()) {
            dto.setBranchName(areaNames.get(0));
        }
        dto.setDepartmentId(employee.getDepartmentId());
        dto.setPositionId(employee.getPositionId());
        dto.setHireDate(employee.getHireDate());
        dto.setContractEndDate(employee.getContractEndDate());
        dto.setAnnualLeaveDuration(employee.getAnnualLeaveDuration());
        dto.setAnnualLeaveBalance(employee.getAnnualLeaveBalance());
        dto.setLeaveBalanceStartDate(employee.getLeaveBalanceStartDate());
        dto.setFatherName(employee.getFatherName());
        dto.setGroupName(employee.getGroupName());
        dto.setSalary(employee.getSalary());
        dto.setHourlyRate(employee.getHourlyRate());
        dto.setAllowance(employee.getAllowance());
        dto.setEmergencyContact(employee.getEmergencyContact());
        dto.setAddress(employee.getAddress());
        dto.setNotes(employee.getNotes());
        dto.setArea(employee.getArea());
        dto.setShiftType(employee.getShiftType());
        dto.setTimetableId(employee.getTimetableId());
        dto.setDeviceIds(employeeDeviceIds != null
                ? employeeDeviceIds.getOrDefault(employee.getId(), List.of())
                : getEmployeeDeviceIds(employee.getId()));
        dto.setDoorAccess(getEmployeeDoorAccess(employee.getId()));
        dto.setEmploymentStatus(employee.getEmploymentStatus());
        if (EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus())) {
            dto.setPendingDeviceRemovals(removalJobRepository
                    .countByTenantIdAndEmployeeIdAndCompletedFalse(employee.getTenantId(), employee.getId()));
        }
        dto.setCreatedAt(employee.getCreatedAt());
        dto.setUpdatedAt(employee.getUpdatedAt());

        if (employee.getDepartmentId() != null) {
            if (departmentNames != null) {
                dto.setDepartmentName(departmentNames.get(employee.getDepartmentId()));
            } else {
                departmentRepository.findById(employee.getDepartmentId())
                        .ifPresent(d -> dto.setDepartmentName(d.getDepartmentName()));
            }
        }
        if (employee.getPositionId() != null) {
            if (positionNames != null) {
                dto.setPositionName(positionNames.get(employee.getPositionId()));
            } else {
                positionRepository.findById(employee.getPositionId())
                        .ifPresent(p -> dto.setPositionName(p.getPositionName()));
            }
        }

        return dto;
    }

    private List<EmployeeResponseDTO> mapEmployeeListToDTOs(List<Employee> employees) {
        if (employees.isEmpty()) return List.of();
        List<Long> employeeIds = employees.stream().map(Employee::getId).toList();
        Set<Long> deptIds = employees.stream()
                .filter(e -> e.getDepartmentId() != null)
                .map(Employee::getDepartmentId)
                .collect(Collectors.toSet());
        Set<Long> posIds = employees.stream()
                .filter(e -> e.getPositionId() != null)
                .map(Employee::getPositionId)
                .collect(Collectors.toSet());
        Map<Long, String> deptNames = departmentRepository.findAllById(deptIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getDepartmentName));
        Map<Long, String> posNames = positionRepository.findAllById(posIds).stream()
                .collect(Collectors.toMap(Position::getId, Position::getPositionName));
        Map<Long, List<Long>> employeeDeviceIds = employeeDeviceAccessRepository.findByEmployeeIdIn(employeeIds).stream()
                .collect(Collectors.groupingBy(
                        EmployeeDeviceAccess::getEmployeeId,
                        Collectors.mapping(EmployeeDeviceAccess::getDeviceConfigId,
                                Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
                                        .distinct()
                                        .sorted(Comparator.naturalOrder())
                                        .toList()))
                ));
        EmployeeAreaAssignmentService.AreaBatch areaBatch =
                employeeAreaAssignmentService.loadAreaBatch(employeeIds);
        return employees.stream()
                .map(e -> toResponseDTO(e, deptNames, posNames, employeeDeviceIds, areaBatch))
                .collect(Collectors.toList());
    }

    private Long resolvePrimaryAreaId(List<Long> areaIds, Long requestedPrimaryAreaId) {
        if (requestedPrimaryAreaId != null && areaIds.contains(requestedPrimaryAreaId)) {
            return requestedPrimaryAreaId;
        }
        return areaIds.isEmpty() ? null : areaIds.get(0);
    }

    private void enforceEmployeeQuota(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        tenantRepository.findById(tenantId).ifPresent(tenant -> {
            Integer max = tenant.getMaxEmployees();
            if (max == null || max <= 0) {
                return;
            }
            long current = employeeRepository.countByTenantId(tenantId);
            if (current >= max) {
                throw new BadRequestException(
                        "Employee limit reached for this tenant (" + max + "). Upgrade the subscription to add more.");
            }
        });
    }

    private List<Long> getEmployeeDeviceIds(Long employeeId) {
        if (employeeId == null) {
            return Collections.emptyList();
        }
        return employeeDeviceAccessRepository.findByEmployeeId(employeeId).stream()
                .map(EmployeeDeviceAccess::getDeviceConfigId)
                .distinct()
                .sorted()
                .toList();
    }

    private PaginatedResponse<EmployeeResponseDTO> buildPaginatedResponse(Page<Employee> page) {
        List<EmployeeResponseDTO> content = mapEmployeeListToDTOs(page.getContent());
        return PaginatedResponse.of(content, page.getTotalElements(),
                page.getTotalPages(), page.getNumber(), page.getSize());
    }

    private Timetable validateWorkReferences(EmployeeDTO dto, Long tenantId, Long primaryAreaId) {
        Long departmentId = dto.getDepartmentId();
        if (departmentId == null || !departmentRepository.existsById(departmentId)) {
            throw new ResourceNotFoundException("Department", departmentId);
        }

        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Department", departmentId));
        if (tenantId != null && !tenantId.equals(department.getTenantId())) {
            throw new BadRequestException("Seçilmiş departament cari şirkətə aid deyil.");
        }
        if (tenantId != null && primaryAreaId != null && !Boolean.TRUE.equals(department.getAllAreas())
                && !primaryAreaId.equals(department.getBranchId())) {
            throw new BadRequestException("Seçilmiş departament əsas əraziyə aid deyil.");
        }

        if (dto.getPositionId() != null) {
            Position position = positionRepository.findById(dto.getPositionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Position", dto.getPositionId()));
            if (!departmentId.equals(position.getDepartmentId())) {
                throw new BadRequestException("Seçilmiş vəzifə departamentə aid deyil.");
            }
            if (tenantId != null && !tenantId.equals(position.getTenantId())) {
                throw new BadRequestException("Seçilmiş vəzifə cari şirkətə aid deyil.");
            }
        }

        Long timetableId = dto.getTimetableId();
        if (timetableId != null) {
            Timetable timetable = timetableRepository.findById(timetableId)
                    .orElseThrow(() -> new ResourceNotFoundException("Timetable", timetableId));
            if (tenantId != null && !tenantId.equals(timetable.getTenantId())) {
                throw new BadRequestException("Seçilmiş iş cədvəli cari şirkətə aid deyil.");
            }
            return timetable;
        }
        return null;
    }

    private void applyTimetableShiftType(Employee employee, Timetable timetable) {
        if (timetable != null && timetable.getShiftType() != null && !timetable.getShiftType().isBlank()) {
            employee.setShiftType(timetable.getShiftType());
        }
    }

    private void syncEmployeeToDevicesSafely(Employee employee, List<Long> assignedDeviceIds) {
        if (assignedDeviceIds == null || assignedDeviceIds.isEmpty()) {
            return;
        }
        try {
            List<DeviceConfig> devices = deviceConfigRepository.findAllById(assignedDeviceIds);
            List<Long> isapiDeviceIds = devices.stream()
                    .map(DeviceConfig::getDeviceId)
                    .filter(id -> id != null && !id.isBlank())
                    .map(Long::valueOf)
                    .distinct()
                    .toList();
            isapiEmployeeUserSyncService.syncEmployee(employee, isapiDeviceIds);
        } catch (RuntimeException ex) {
            log.warn("Employee {} was saved but device sync failed: {}", employee.getEmployeeId(), ex.getMessage());
        }
    }

    private void deleteEmployeeFromDevicesSafely(Employee employee, List<Long> deviceConfigIds) {
        try {
            deleteEmployeeFromDevices(employee, deviceConfigIds);
        } catch (RuntimeException ex) {
            log.warn("Employee {} was saved but removal from previous devices failed: {}",
                    employee.getEmployeeId(), ex.getMessage());
        }
    }

    private void deleteEmployeeFromDevices(Employee employee, List<Long> deviceConfigIds) {
        if (deviceConfigIds == null || deviceConfigIds.isEmpty()) {
            return;
        }
        List<Long> isapiDeviceIds = deviceConfigRepository.findAllById(deviceConfigIds).stream()
                .map(DeviceConfig::getDeviceId)
                .filter(id -> id != null && !id.isBlank())
                .map(Long::valueOf)
                .distinct()
                .toList();
        if (!isapiDeviceIds.isEmpty()) {
            isapiEmployeeUserSyncService.deleteEmployee(employee, isapiDeviceIds);
        }
    }

    private String generateEmployeeId(Long tenantId) {
        long sequence = tenantId != null
                ? employeeRepository.countByTenantId(tenantId) + 1
                : employeeRepository.count() + 1;
        String candidate;
        do {
            candidate = String.format("EMP%04d", sequence++);
        } while (employeeIdExists(tenantId, candidate));
        return candidate;
    }

    private void validateEmployeeIdAvailable(Long tenantId, String employeeId) {
        if (employeeIdExists(tenantId, employeeId)) {
            throw new BadRequestException("Bu əməkdaş ID artıq mövcuddur: " + employeeId);
        }
    }

    private boolean employeeIdExists(Long tenantId, String employeeId) {
        return tenantId != null
                ? employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(tenantId, employeeId).isPresent()
                : employeeRepository.findByEmployeeIdIgnoreCase(employeeId).isPresent();
    }

    public List<String> getDistinctAreas() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return java.util.Collections.emptyList();
        }
        return employeeRepository.findDistinctAreasByTenantId(tenantId);
    }
}
