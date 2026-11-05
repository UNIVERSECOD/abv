package com.hic.service;

import com.hic.dto.EmployeeDTO;
import com.hic.dto.EmployeeResponseDTO;
import com.hic.dto.EmployeeSearchResultDTO;
import com.hic.dto.EmployeeTerminationResultDTO;
import com.hic.dto.DeviceSyncDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.DeviceSyncException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.Department;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.model.EmployeeDeviceAccess;
import com.hic.model.Employee.EmploymentStatus;
import com.hic.model.Door;
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
import com.hic.util.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TimetableRepository timetableRepository;

    @Mock
    private EmployeeFaceImageService employeeFaceImageService;

    @Mock
    private DeviceConfigRepository deviceConfigRepository;

    @Mock
    private DoorRepository doorRepository;

    @Mock
    private EmployeeDeviceAccessRepository employeeDeviceAccessRepository;

    @Mock
    private IsapiEmployeeUserSyncService isapiEmployeeUserSyncService;

    @Mock
    private EmployeeDeviceRemovalService employeeDeviceRemovalService;
    @Mock
    private com.hic.repository.EmployeeDeviceRemovalJobRepository removalJobRepository;

    @Mock
    private UserScopeService userScopeService;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private ShiftAssignmentService shiftAssignmentService;

    @Mock
    private EmployeeAreaAssignmentService employeeAreaAssignmentService;

    @InjectMocks
    private EmployeeService employeeService;

    private Department testDepartment;
    private Employee testEmployee;
    private EmployeeDTO testEmployeeDTO;

    @BeforeEach
    void setUp() {
        testDepartment = new Department();
        testDepartment.setId(1L);
        testDepartment.setDepartmentName("Engineering");
        testDepartment.setBranchId(1L);

        testEmployee = new Employee();
        testEmployee.setId(1L);
        testEmployee.setEmployeeId("EMP202401001");
        testEmployee.setFirstName("John");
        testEmployee.setLastName("Doe");
        testEmployee.setDepartmentId(1L);
        testEmployee.setEmploymentStatus(EmploymentStatus.ACTIVE);

        testEmployeeDTO = new EmployeeDTO();
        testEmployeeDTO.setFirstName("John");
        testEmployeeDTO.setLastName("Doe");
        testEmployeeDTO.setDepartmentId(1L);
        testEmployeeDTO.setHireDate(LocalDate.now());

        lenient().when(employeeFaceImageService.getLatestEmployeeFacePublicUrl(anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(departmentRepository.existsById(1L)).thenReturn(true);
        lenient().when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));
        lenient().when(timetableRepository.findById(anyLong())).thenAnswer(invocation -> {
            Timetable timetable = new Timetable();
            timetable.setId(invocation.getArgument(0));
            timetable.setShiftType("STANDARD");
            return Optional.of(timetable);
        });
        lenient().when(userScopeService.resolveBranchScope(any())).thenReturn(null);
        lenient().when(employeeAreaAssignmentService.normalizeAndValidateAreaIds(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<Long> requested = invocation.getArgument(0);
                    Long primary = invocation.getArgument(1);
                    if (primary != null) {
                        return requested == null || requested.isEmpty()
                                ? List.of(primary)
                                : java.util.stream.Stream.concat(java.util.stream.Stream.of(primary), requested.stream())
                                .distinct()
                                .toList();
                    }
                    return requested == null ? List.of() : requested;
                });
        lenient().when(employeeAreaAssignmentService.replaceEmployeeAreas(any(), anyList(), any()))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(), List.of()));
        lenient().when(employeeAreaAssignmentService.loadAreaBatch(any()))
                .thenReturn(new EmployeeAreaAssignmentService.AreaBatch(Map.of(), Map.of()));
        lenient().when(employeeAreaAssignmentService.getAreaIds(anyLong())).thenReturn(List.of());
        lenient().when(employeeAreaAssignmentService.resolveDeviceIdsForAreas(any(), any())).thenReturn(List.of());
    }

    @Test
    void getById_existingEmployee_returnsDTO() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        EmployeeResponseDTO result = employeeService.getById(1L);

        assertThat(result).isNotNull();
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Doe");
        assertThat(result.getDepartmentName()).isEqualTo("Engineering");
    }

    @Test
    void getById_nonExistentEmployee_throwsResourceNotFoundException() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employeeService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_validDTO_savesEmployee() {
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.count()).thenReturn(0L);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        EmployeeResponseDTO result = employeeService.create(testEmployeeDTO);

        assertThat(result).isNotNull();
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getEmploymentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        verify(employeeRepository).save(any(Employee.class));
        verify(isapiEmployeeUserSyncService, never()).syncEmployee(any(Employee.class), anyList());
    }

    @Test
    void sharedDepartmentAllowsEmployeeFromAnotherAreaButLocalDepartmentDoesNot() {
        TenantContext.setTenantId(7L);
        try {
            testDepartment.setTenantId(7L);
            testEmployeeDTO.setBranchId(2L);
            testEmployeeDTO.setFinNumber("SHARED1");
            testDepartment.setAllAreas(false);
            assertThatThrownBy(() -> employeeService.createForImport(testEmployeeDTO, "SHARED1"))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("əsas əraziyə");
            testDepartment.setAllAreas(true);
            when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> {
                Employee employee = i.getArgument(0); employee.setId(1L); return employee;
            });
            employeeService.createForImport(testEmployeeDTO, "SHARED1");
            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getDepartmentId()).isEqualTo(1L);
            assertThat(saved.getValue().getBranchId()).isEqualTo(2L);
            verifyNoInteractions(isapiEmployeeUserSyncService);
        } finally { TenantContext.clear(); }
    }

    @Test
    void create_selectedPositionAndTimetable_areReturnedImmediately() {
        Position position = new Position();
        position.setId(5L);
        position.setPositionName("Surveyor");
        position.setDepartmentId(1L);
        Timetable timetable = new Timetable();
        timetable.setId(7L);
        timetable.setShiftType("STANDARD");

        testEmployeeDTO.setPositionId(5L);
        testEmployeeDTO.setTimetableId(7L);
        testEmployeeDTO.setShiftType("STANDARD");
        testEmployeeDTO.setEmergencyContact("+994501112233");
        testEmployeeDTO.setAddress("Baku");

        when(positionRepository.findById(5L)).thenReturn(Optional.of(position));
        when(timetableRepository.findById(7L)).thenReturn(Optional.of(timetable));
        when(employeeRepository.count()).thenReturn(0L);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            employee.setId(1L);
            return employee;
        });

        EmployeeResponseDTO result = employeeService.create(testEmployeeDTO);

        assertThat(result.getPositionId()).isEqualTo(5L);
        assertThat(result.getPositionName()).isEqualTo("Surveyor");
        assertThat(result.getTimetableId()).isEqualTo(7L);
        assertThat(result.getShiftType()).isEqualTo("STANDARD");
        assertThat(result.getEmergencyContact()).isEqualTo("+994501112233");
        assertThat(result.getAddress()).isEqualTo("Baku");
    }

    @Test
    void create_positionMustBelongToSelectedDepartment() {
        Position position = new Position();
        position.setId(5L);
        position.setDepartmentId(2L);
        testEmployeeDTO.setPositionId(5L);
        when(positionRepository.findById(5L)).thenReturn(Optional.of(position));

        assertThatThrownBy(() -> employeeService.create(testEmployeeDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("vəzifə departamentə aid deyil");
        verify(employeeRepository, never()).save(any(Employee.class));
    }

    @Test
    void create_departmentMustBelongToPrimaryArea() {
        testDepartment.setTenantId(1L);
        testDepartment.setBranchId(2L);
        testEmployeeDTO.setBranchId(1L);
        testEmployeeDTO.setAreaIds(List.of(1L));
        TenantContext.setTenantId(1L);
        try {
            assertThatThrownBy(() -> employeeService.create(testEmployeeDTO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("departament əsas əraziyə aid deyil");
            verify(employeeRepository, never()).save(any(Employee.class));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void create_timetableMustBelongToCurrentTenant() {
        testDepartment.setTenantId(1L);
        testDepartment.setBranchId(1L);
        testEmployeeDTO.setBranchId(1L);
        testEmployeeDTO.setAreaIds(List.of(1L));
        testEmployeeDTO.setTimetableId(7L);
        Timetable timetable = new Timetable();
        timetable.setId(7L);
        timetable.setTenantId(2L);
        timetable.setShiftType("STANDARD");
        when(timetableRepository.findById(7L)).thenReturn(Optional.of(timetable));

        TenantContext.setTenantId(1L);
        try {
            assertThatThrownBy(() -> employeeService.create(testEmployeeDTO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("iş cədvəli cari şirkətə aid deyil");
            verify(employeeRepository, never()).save(any(Employee.class));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void create_isapiSyncFails_stillPersistsEmployee() {
        DeviceConfig targetDevice = device(10L, "101", 1L);
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.count()).thenReturn(0L);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));
        when(employeeAreaAssignmentService.replaceEmployeeAreas(any(Employee.class), anyList(), any()))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(10L), List.of()));
        when(deviceConfigRepository.findAllById(List.of(10L))).thenReturn(List.of(targetDevice));
        doThrow(new DeviceSyncException("ISAPI user sync is unavailable"))
                .when(isapiEmployeeUserSyncService).syncEmployee(any(Employee.class), anyList());

        EmployeeResponseDTO result = employeeService.create(testEmployeeDTO);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        verify(employeeRepository).save(any(Employee.class));
        verify(isapiEmployeeUserSyncService).syncEmployee(any(Employee.class), anyList());
    }

    @Test
    void createForImportStoresAssignmentsWithoutCallingPhysicalDevices() {
        testEmployeeDTO.setBranchId(1L);
        testEmployeeDTO.setAreaIds(List.of(1L));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> {
            Employee employee = invocation.getArgument(0);
            employee.setId(1L);
            return employee;
        });
        when(employeeAreaAssignmentService.replaceEmployeeAreas(any(Employee.class), eq(List.of(1L)), eq(1L)))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(10L), List.of()));

        employeeService.createForImport(testEmployeeDTO, "BULK-001");

        ArgumentCaptor<Employee> employeeCaptor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(employeeCaptor.capture());
        assertThat(employeeCaptor.getValue().getEmployeeId()).isEqualTo("BULK-001");
        verify(employeeAreaAssignmentService)
                .replaceEmployeeAreas(any(Employee.class), eq(List.of(1L)), eq(1L));
        verify(deviceConfigRepository, never()).findAllById(any());
        verify(isapiEmployeeUserSyncService, never()).syncEmployee(any(Employee.class), anyList());
    }

    @Test
    void create_duplicateFin_throwsBadRequestException() {
        testEmployeeDTO.setFinNumber("ABC1234567");
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.findByFinNumber("ABC1234567")).thenReturn(Optional.of(testEmployee));

        assertThatThrownBy(() -> employeeService.create(testEmployeeDTO))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("FIN kodu");
    }

    @Test
    void create_invalidDepartment_throwsResourceNotFoundException() {
        when(departmentRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> employeeService.create(testEmployeeDTO))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_existingEmployee_updatesAndReturns() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenReturn(testEmployee);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        testEmployeeDTO.setFirstName("Jane");
        EmployeeResponseDTO result = employeeService.update(1L, testEmployeeDTO);

        assertThat(result).isNotNull();
        verify(employeeRepository).save(any(Employee.class));
    }

    @Test
    void unrelatedEmployeeEditPreservesOriginalBalanceAndCompatibilityOffset() {
        testEmployee.setAnnualLeaveBalance(12); testEmployee.setLegacyLeaveBalanceOffset(7);
        testEmployeeDTO.setAnnualLeaveBalance(19); testEmployeeDTO.setFirstName("Changed");
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = employeeService.update(1L, testEmployeeDTO);
        assertThat(testEmployee.getAnnualLeaveBalance()).isEqualTo(12);
        assertThat(testEmployee.getLegacyLeaveBalanceOffset()).isEqualTo(7);
        assertThat(result.getAnnualLeaveBalance()).isEqualTo(19);
    }

    @Test
    void explicitOpeningBalanceEditReplacesEffectiveAmountWithoutRetainingOffset() {
        testEmployee.setAnnualLeaveBalance(12); testEmployee.setLegacyLeaveBalanceOffset(7);
        testEmployeeDTO.setAnnualLeaveBalance(25);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = employeeService.update(1L, testEmployeeDTO);
        assertThat(testEmployee.getAnnualLeaveBalance()).isEqualTo(25);
        assertThat(testEmployee.getLegacyLeaveBalanceOffset()).isZero();
        assertThat(result.getAnnualLeaveBalance()).isEqualTo(25);
    }

    @Test
    void update_preservesFieldsThatAreNotEditableInTheEmployeeForm() {
        testEmployee.setCardId("CARD-100");
        testEmployee.setFaceId("FACE-100");
        testEmployee.setGroupName("Legacy group");
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeResponseDTO result = employeeService.update(1L, testEmployeeDTO);

        assertThat(result.getCardId()).isEqualTo("CARD-100");
        assertThat(result.getFaceId()).isEqualTo("FACE-100");
        assertThat(result.getGroupName()).isEqualTo("Legacy group");
    }

    @Test
    void update_preservesSelectedDepartmentAndTimetable() {
        Department newDepartment = new Department();
        newDepartment.setId(2L);
        newDepartment.setDepartmentName("Survey");
        testEmployee.setTimetableId(10L);
        testEmployeeDTO.setDepartmentId(2L);
        testEmployeeDTO.setTimetableId(20L);

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(2L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(departmentRepository.findById(2L)).thenReturn(Optional.of(newDepartment));

        EmployeeResponseDTO result = employeeService.update(1L, testEmployeeDTO);

        assertThat(result.getDepartmentId()).isEqualTo(2L);
        assertThat(result.getTimetableId()).isEqualTo(20L);
        verify(shiftAssignmentService).syncScheduleFromEmployee(
                testEmployee, 10L, 20L, com.hic.util.AppTimeZone.today());
    }

    @Test
    void update_isapiSyncFails_stillUpdatesEmployee() {
        DeviceConfig targetDevice = device(10L, "101", 1L);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenReturn(testEmployee);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));
        when(employeeAreaAssignmentService.replaceEmployeeAreas(any(Employee.class), anyList(), any()))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(10L), List.of()));
        when(deviceConfigRepository.findAllById(List.of(10L))).thenReturn(List.of(targetDevice));
        doThrow(new DeviceSyncException("sync unavailable"))
                .when(isapiEmployeeUserSyncService).syncEmployee(any(Employee.class), anyList());

        EmployeeResponseDTO result = employeeService.update(1L, testEmployeeDTO);

        assertThat(result).isNotNull();
        verify(employeeRepository).save(any(Employee.class));
        verify(isapiEmployeeUserSyncService).syncEmployee(any(Employee.class), anyList());
    }

    @Test
    void terminate_enqueuesExplicitAndAreaDevices() {
        testEmployee.setBranchId(1L);
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        EmployeeDeviceAccess access = new EmployeeDeviceAccess();
        access.setDeviceConfigId(10L);
        when(employeeDeviceAccessRepository.findByEmployeeId(1L)).thenReturn(List.of(access));
        when(employeeAreaAssignmentService.getAreaIds(1L)).thenReturn(List.of(1L));
        when(employeeAreaAssignmentService.resolveDeviceIdsForAreas(List.of(1L), null))
                .thenReturn(List.of(10L, 11L));
        var expected = new EmployeeTerminationResultDTO(1L, 2, 1, 1, List.of("Gözlənilir"));
        when(employeeDeviceRemovalService.terminate(eq(testEmployee), anySet())).thenReturn(expected);

        assertThat(employeeService.terminate(1L)).isSameAs(expected);
        verify(employeeDeviceRemovalService).terminate(testEmployee, java.util.Set.of(10L, 11L));
        verify(employeeRepository, never()).delete(any(Employee.class));
        verify(employeeFaceImageService, never()).deleteFaceImages(anyLong());
    }

    @Test
    void terminate_nonExistentEmployee_throwsResourceNotFoundException() {
        when(employeeRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> employeeService.terminate(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAll_returnsPaginatedResponse() {
        Page<Employee> page = new PageImpl<>(List.of(testEmployee));
        when(employeeRepository.findActiveByFilters(
                isNull(), eq(EmploymentStatus.TERMINATED), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class)))
                .thenReturn(page);
        when(departmentRepository.findAllById(anyCollection())).thenReturn(List.of(testDepartment));
        when(positionRepository.findAllById(anyCollection())).thenReturn(Collections.emptyList());

        var result = employeeService.getAll(0, 20, null);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1L);
    }

    @Test
    void getByDepartment_returnsBatchLoadedDTOs() {
        when(employeeRepository.findByDepartmentIdAndEmploymentStatusNot(
                1L, EmploymentStatus.TERMINATED)).thenReturn(List.of(testEmployee));
        when(departmentRepository.findAllById(anyCollection())).thenReturn(List.of(testDepartment));
        when(positionRepository.findAllById(anyCollection())).thenReturn(Collections.emptyList());

        List<EmployeeResponseDTO> result = employeeService.getByDepartment(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDepartmentName()).isEqualTo("Engineering");
        // Verify no extra findById calls (N+1 prevention)
        verify(departmentRepository, never()).findById(anyLong());
        verify(positionRepository, never()).findById(anyLong());
    }

    @Test
    void searchEmployees_matchesFinAndReturnsMinimalResults() {
        testEmployee.setFinNumber("FIN12345");
        when(employeeRepository.searchMinimal(isNull(), eq("FIN12345"), any(Pageable.class)))
                .thenReturn(List.of(testEmployee));
        when(departmentRepository.findAllById(anyCollection())).thenReturn(List.of(testDepartment));

        List<EmployeeSearchResultDTO> result = employeeService.searchEmployees("FIN12345");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEmployeePk()).isEqualTo(1L);
        assertThat(result.get(0).getFinNumber()).isEqualTo("FIN12345");
        assertThat(result.get(0).getDepartmentName()).isEqualTo("Engineering");
    }

    @Test
    void update_multipleAreas_usesAssignmentServiceResult() {
        testEmployee.setBranchId(1L);
        testEmployeeDTO.setBranchId(2L);
        testEmployeeDTO.setAreaIds(List.of(2L, 3L));

        com.hic.model.DeviceConfig branchDevice = new com.hic.model.DeviceConfig();
        branchDevice.setId(20L);
        branchDevice.setDeviceId("202");
        branchDevice.setBranchId(2L);
        branchDevice.setTenantId(testEmployee.getTenantId());

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeAreaAssignmentService.normalizeAndValidateAreaIds(List.of(2L, 3L), 2L, null))
                .thenReturn(List.of(2L, 3L));
        when(employeeAreaAssignmentService.replaceEmployeeAreas(testEmployee, List.of(2L, 3L), 2L))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(20L), List.of()));
        when(deviceConfigRepository.findAllById(List.of(20L))).thenReturn(List.of(branchDevice));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        EmployeeResponseDTO result = employeeService.update(1L, testEmployeeDTO);

        assertThat(result).isNotNull();
        assertThat(testEmployee.getBranchId()).isEqualTo(2L);
        verify(employeeAreaAssignmentService).replaceEmployeeAreas(testEmployee, List.of(2L, 3L), 2L);
        verify(isapiEmployeeUserSyncService).syncEmployee(testEmployee, List.of(202L));
    }

    @Test
    void update_preservesManualAccessReturnedByAssignmentService() {
        testEmployee.setBranchId(1L);
        testEmployeeDTO.setBranchId(1L);

        com.hic.model.DeviceConfig wrongBranchDevice = new com.hic.model.DeviceConfig();
        wrongBranchDevice.setId(10L);
        wrongBranchDevice.setDeviceId("101");
        wrongBranchDevice.setBranchId(2L);
        wrongBranchDevice.setTenantId(testEmployee.getTenantId());

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenReturn(testEmployee);
        when(employeeAreaAssignmentService.replaceEmployeeAreas(testEmployee, List.of(1L), 1L))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(10L), List.of()));
        when(deviceConfigRepository.findAllById(List.of(10L))).thenReturn(List.of(wrongBranchDevice));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        EmployeeResponseDTO result = employeeService.update(1L, testEmployeeDTO);

        assertThat(result).isNotNull();
        verify(isapiEmployeeUserSyncService).syncEmployee(testEmployee, List.of(101L));
    }

    @Test
    void update_syncsEveryDeviceInEmployeesBranch() {
        testEmployee.setBranchId(1L);
        testEmployeeDTO.setBranchId(1L);
        DeviceConfig firstDevice = device(10L, "101", 1L);
        DeviceConfig secondDevice = device(11L, "102", 1L);

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeAreaAssignmentService.replaceEmployeeAreas(testEmployee, List.of(1L), 1L))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(10L, 11L), List.of()));
        when(deviceConfigRepository.findAllById(List.of(10L, 11L)))
                .thenReturn(List.of(firstDevice, secondDevice));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        employeeService.update(1L, testEmployeeDTO);

        verify(isapiEmployeeUserSyncService).syncEmployee(testEmployee, List.of(101L, 102L));
    }

    @Test
    void update_branchChangeRemovesOldDeviceAndSyncsNewDevice() {
        testEmployee.setBranchId(1L);
        testEmployeeDTO.setBranchId(2L);
        DeviceConfig oldDevice = device(10L, "101", 1L);
        DeviceConfig newDevice = device(20L, "202", 2L);

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(testEmployee));
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));
        when(employeeAreaAssignmentService.replaceEmployeeAreas(testEmployee, List.of(2L), 2L))
                .thenReturn(new EmployeeAreaAssignmentService.AssignmentChange(List.of(20L), List.of(10L)));
        when(deviceConfigRepository.findAllById(List.of(10L))).thenReturn(List.of(oldDevice));
        when(deviceConfigRepository.findAllById(List.of(20L))).thenReturn(List.of(newDevice));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(testDepartment));

        employeeService.update(1L, testEmployeeDTO);

        verify(isapiEmployeeUserSyncService).deleteEmployee(testEmployee, List.of(101L));
        verify(isapiEmployeeUserSyncService).syncEmployee(testEmployee, List.of(202L));
    }

    private DeviceConfig device(Long id, String isapiDeviceId, Long branchId) {
        DeviceConfig device = new DeviceConfig();
        device.setId(id);
        device.setDeviceId(isapiDeviceId);
        device.setBranchId(branchId);
        device.setTenantId(testEmployee.getTenantId());
        return device;
    }

    private DeviceSyncDTO.DeviceConfigDTO bridgeDevice(Long id, DeviceConfig localDevice) {
        DeviceSyncDTO.DeviceConfigDTO dto = new DeviceSyncDTO.DeviceConfigDTO();
        dto.setId(id);
        dto.setDeviceIp(localDevice.getDeviceIp());
        dto.setDeviceName(localDevice.getDeviceName());
        return dto;
    }
}
