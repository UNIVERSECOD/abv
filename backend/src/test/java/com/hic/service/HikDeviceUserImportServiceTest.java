package com.hic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.dto.DeviceEmployeeImportDTO;
import com.hic.model.Branch;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.model.EmployeeArea;
import com.hic.repository.BranchRepository;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeDeviceAccessRepository;
import com.hic.repository.EmployeeAreaRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.TenantRepository;
import com.hic.util.EncryptionUtil;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HikDeviceUserImportServiceTest {

    @Mock private RestTemplate restTemplate;
    @Mock private DeviceConfigRepository deviceConfigRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeDeviceAccessRepository employeeDeviceAccessRepository;
    @Mock private EmployeeAreaRepository employeeAreaRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private EmployeeFaceImageService employeeFaceImageService;
    @Mock private IsapiEmployeeUserSyncService isapiEmployeeUserSyncService;

    private HikDeviceUserImportService service;

    @BeforeEach
    void setUp() {
        service = new HikDeviceUserImportService(
                restTemplate,
                deviceConfigRepository,
                employeeRepository,
                employeeDeviceAccessRepository,
                employeeAreaRepository,
                branchRepository,
                tenantRepository,
                encryptionUtil,
                new ObjectMapper(),
                employeeFaceImageService,
                isapiEmployeeUserSyncService);
        ReflectionTestUtils.setField(service, "isapiBaseUrl", "http://isapi:8081");
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void importUsersFromBranch_createsPrefixedEmployee_mergesDevices_skipsConflict() {
        Branch branch = branch(10L, "Baku", "BAK");
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch));

        DeviceConfig entry = device(1L, "101", "Entry", "10.0.0.1", 10L);
        DeviceConfig exit = device(2L, "102", "Exit", "10.0.0.2", 10L);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(entry, exit));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"employeeNo":"1001","name":"Ali Valiyev","gender":"male","beginTime":"2024-01-01T00:00:00"},
                         {"employeeNo":"1002","name":"A","gender":"male"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"employeeNo":"1001","name":"Ali Valiyev","gender":"male"},
                         {"employeeNo":"1002","name":"B","gender":"male"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));

        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(eq(1L), eq(10L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(eq(1L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndEmployeeIdIn(eq(1L), any()))
                .thenReturn(List.of());
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(50L);
            return e;
        });
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(anyLong(), anyLong()))
                .thenReturn(false);

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, false));

        assertThat(result.getBranchPrefix()).isEqualTo("BAK");
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getSkippedConflict()).isEqualTo(1);
        assertThat(result.getCreatedPersons().get(0).getEmployeeId()).isEqualTo("BAK-1001");
        assertThat(result.getCreatedPersons().get(0).getDeviceEmployeeNo()).isEqualTo("1001");
        assertThat(result.getCreatedPersons().get(0).getHomeBranchId()).isEqualTo(10L);

        ArgumentCaptor<Employee> empCaptor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository, times(1)).save(empCaptor.capture());
        assertThat(empCaptor.getValue().getEmployeeId()).isEqualTo("BAK-1001");
        assertThat(empCaptor.getValue().getDeviceEmployeeNo()).isEqualTo("1001");
        assertThat(empCaptor.getValue().getBranchId()).isEqualTo(10L);
        ArgumentCaptor<com.hic.model.EmployeeDeviceAccess> accessCaptor =
                ArgumentCaptor.forClass(com.hic.model.EmployeeDeviceAccess.class);
        verify(employeeDeviceAccessRepository, times(2)).save(accessCaptor.capture());
        assertThat(accessCaptor.getAllValues())
                .allSatisfy(access -> {
                    assertThat(access.getAssignmentSource())
                            .isEqualTo(com.hic.model.EmployeeDeviceAccess.AssignmentSource.AREA);
                    assertThat(access.getSourceBranchId()).isEqualTo(10L);
                });
        ArgumentCaptor<EmployeeArea> areaCaptor = ArgumentCaptor.forClass(EmployeeArea.class);
        verify(employeeAreaRepository).save(areaCaptor.capture());
        assertThat(areaCaptor.getValue().getBranchId()).isEqualTo(10L);
        assertThat(areaCaptor.getValue().isPrimary()).isTrue();
        verify(isapiEmployeeUserSyncService, never()).syncEmployee(any(), any());
    }

    @Test
    void importUsersFromBranch_doesNotRelinkOrResyncTerminatedEmployee() {
        Branch branch = branch(10L, "Baku", "BAK");
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch));
        DeviceConfig entry = device(1L, "101", "Entry", "10.0.0.1", 10L);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(entry));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"employeeNo":"1001","name":"Ali Valiyev"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));

        Employee terminated = new Employee();
        terminated.setId(50L);
        terminated.setTenantId(1L);
        terminated.setEmployeeId("BAK-1001");
        terminated.setDeviceEmployeeNo("1001");
        terminated.setFirstName("Ali");
        terminated.setLastName("Valiyev");
        terminated.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);
        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(1L, "BAK-1001"))
                .thenReturn(Optional.of(terminated));

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, false));

        assertThat(result.getSkippedExisting()).isEqualTo(1);
        assertThat(result.getCreated()).isZero();
        assertThat(result.getAccessLinked()).isZero();
        verify(employeeRepository, never()).save(any(Employee.class));
        verify(employeeDeviceAccessRepository, never()).save(any());
        verify(employeeAreaRepository, never()).save(any());
        verify(isapiEmployeeUserSyncService, never()).syncEmployee(any(), any());
    }

    @Test
    void importUsersFromBranch_syncsFaceWhenFpidMatchesDeviceEmployeeNo() {
        Branch branch = branch(10L, "Baku", "BAK");
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch));
        DeviceConfig entry = device(1L, "101", "Entry", "10.0.0.1", 10L);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(entry));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"employeeNo":"1001","name":"Ali Valiyev"},
                         {"employeeNo":"1002","name":"No Face User"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"fpid":"1001","faceUrl":"http://10.0.0.1/LOCALS/pic/face1.jpg"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/1001/download"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"employeeNo":"1001","status":"SUCCESS","imageBase64":"AQID"}
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/1002/download"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        {"employeeNo":"1002","status":"SUCCESS","imageBase64":"BAUG"}
                        """));

        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(eq(1L), eq(10L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(eq(1L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndEmployeeIdIn(eq(1L), any()))
                .thenReturn(List.of());
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            if ("BAK-1001".equals(e.getEmployeeId())) {
                e.setId(50L);
            } else {
                e.setId(51L);
            }
            return e;
        });
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(anyLong(), anyLong()))
                .thenReturn(false);
        java.util.concurrent.atomic.AtomicBoolean faceSavedFor50 = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean faceSavedFor51 = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(employeeFaceImageService.hasFaceImage(50L)).thenAnswer(inv -> faceSavedFor50.get());
        when(employeeFaceImageService.hasFaceImage(51L)).thenAnswer(inv -> faceSavedFor51.get());
        org.mockito.Mockito.doAnswer(inv -> {
            faceSavedFor50.set(true);
            return null;
        }).when(employeeFaceImageService).saveFaceImageBytes(eq(50L), any(byte[].class), eq("jpg"));
        org.mockito.Mockito.doAnswer(inv -> {
            faceSavedFor51.set(true);
            return null;
        }).when(employeeFaceImageService).saveFaceImageBytes(eq(51L), any(byte[].class), eq("jpg"));

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, false));

        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(result.getFacesSynced()).isEqualTo(2);
        assertThat(result.getFacesSkippedNoMatch()).isZero();
        verify(employeeFaceImageService).saveFaceImageBytes(eq(50L), any(byte[].class), eq("jpg"));
        verify(employeeFaceImageService).saveFaceImageBytes(eq(51L), any(byte[].class), eq("jpg"));
    }

    @Test
    void importUsersFromBranch_crossBranchSamePerson_linksAccessOnly() {
        Branch branch = branch(20L, "Ganja", "GAN");
        when(branchRepository.findById(20L)).thenReturn(Optional.of(branch));
        DeviceConfig entry = device(5L, "201", "Entry", "10.0.1.1", 20L);
        when(deviceConfigRepository.findByBranchId(20L)).thenReturn(List.of(entry));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenAnswer(inv -> {
                    String url = inv.getArgument(0);
                    if (url != null && url.contains("/faces/")) {
                        return ResponseEntity.ok("[]");
                    }
                    return ResponseEntity.ok("""
                            [{"employeeNo":"1001","name":"Ali Valiyev"}]
                            """);
                });

        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(1L, "GAN-1001"))
                .thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(1L, "1001"))
                .thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(1L, 20L, "1001"))
                .thenReturn(List.of());

        Employee existingHome = new Employee();
        existingHome.setId(9L);
        existingHome.setTenantId(1L);
        existingHome.setEmployeeId("BAK-1001");
        existingHome.setDeviceEmployeeNo("1001");
        existingHome.setBranchId(10L);
        existingHome.setFirstName("Ali");
        existingHome.setLastName("Valiyev");
        when(employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(1L, "1001"))
                .thenReturn(List.of(existingHome));
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(9L, 5L))
                .thenReturn(false);
        EmployeeArea homeMembership = new EmployeeArea();
        homeMembership.setEmployeeId(9L);
        homeMembership.setBranchId(10L);
        homeMembership.setPrimary(true);
        when(employeeAreaRepository.findByEmployeeIdOrderByPrimaryDescBranchIdAsc(9L))
                .thenReturn(List.of(homeMembership));

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(20L, null, false));

        assertThat(result.getCreated()).isZero();
        assertThat(result.getCrossBranchLinked()).isEqualTo(1);
        assertThat(result.getAccessLinked()).isEqualTo(1);
        verify(employeeRepository, never()).save(any());
        verify(employeeDeviceAccessRepository).save(any());
        ArgumentCaptor<EmployeeArea> areaCaptor = ArgumentCaptor.forClass(EmployeeArea.class);
        verify(employeeAreaRepository).save(areaCaptor.capture());
        assertThat(areaCaptor.getValue().getBranchId()).isEqualTo(20L);
        assertThat(areaCaptor.getValue().isPrimary()).isFalse();
    }

    @Test
    void importUsersFromBranch_reportsDeviceFailure_continues() {
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch(10L, "Baku", "BAK")));
        DeviceConfig a = device(1L, "101", "A", "10.0.0.1", 10L);
        DeviceConfig b = device(2L, "102", "B", "10.0.0.2", 10L);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(a, b));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenThrow(new RuntimeException("down"));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(new ResponseEntity<>("[]", HttpStatus.OK));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(new ResponseEntity<>("[]", HttpStatus.OK));
        when(encryptionUtil.decrypt(any())).thenReturn("pass");
        when(restTemplate.exchange(
                eq("http://10.0.0.1/ISAPI/AccessControl/UserInfo/Search?format=json"),
                eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(new RuntimeException("basic also failed"));

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, false));

        assertThat(result.getDevicesFailed()).isEqualTo(1);
        assertThat(result.getDevicesScanned()).isEqualTo(2);
    }

    @Test
    void importUsersFromBranch_missingStoredPassword_doesNotTryEmptyCredential() {
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch(10L, "Baku", "BAK")));
        DeviceConfig device = device(1L, "101", "Entry", "10.0.0.1", 10L);
        device.setPasswordEncrypted(null);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(device));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenThrow(new RuntimeException("bridge unavailable"));

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, false));

        assertThat(result.getDevicesFailed()).isEqualTo(1);
        assertThat(result.getDeviceStatuses().get(0).getError()).contains("credential is unavailable");
        verify(restTemplate, never()).exchange(
                eq("http://10.0.0.1/ISAPI/AccessControl/UserInfo/Search?format=json"),
                eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void importUsersFromBranch_writesMissingPersonToSiblingBranchDevice() {
        Branch branch = branch(10L, "Baku", "BAK");
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch));

        DeviceConfig entry = device(1L, "101", "Entry", "10.0.0.1", 10L);
        DeviceConfig exit = device(2L, "102", "Exit", "10.0.0.2", 10L);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(entry, exit));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"employeeNo":"1001","name":"Ali Valiyev","gender":"male","beginTime":"2024-01-01T00:00:00"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));

        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(eq(1L), eq(10L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(eq(1L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndEmployeeIdIn(eq(1L), any()))
                .thenReturn(List.of());
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(77L);
            return e;
        });
        when(employeeRepository.findById(77L)).thenAnswer(inv -> {
            Employee e = new Employee();
            e.setId(77L);
            e.setEmployeeId("BAK-1001");
            e.setDeviceEmployeeNo("1001");
            e.setFirstName("Ali");
            e.setLastName("Valiyev");
            e.setTenantId(1L);
            e.setBranchId(10L);
            return Optional.of(e);
        });
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(eq(77L), eq(1L)))
                .thenReturn(false)
                .thenReturn(true);
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(eq(77L), eq(2L)))
                .thenReturn(false);
        when(employeeFaceImageService.hasFaceImage(77L)).thenReturn(false);
        when(employeeFaceImageService.getLatestFaceImage(77L)).thenReturn(Optional.empty());

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, true));

        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getWrittenToOtherDevices()).isEqualTo(1);
        assertThat(result.getOtherDeviceWriteFailed()).isZero();
        verify(isapiEmployeeUserSyncService).syncEmployee(any(Employee.class), eq(List.of(102L)));
        verify(employeeDeviceAccessRepository, times(2)).save(any());
    }

    @Test
    void resolveBranchPrefix_usesCodeOrName() {
        assertThat(HikDeviceUserImportService.resolveBranchPrefix(branch(1L, "Baku Office", "bak-01")))
                .isEqualTo("BAK01");
        assertThat(HikDeviceUserImportService.resolveBranchPrefix(branch(1L, "Gəncə", null)))
                .isEqualTo("GNC");
        assertThat(HikDeviceUserImportService.buildPrefixedEmployeeId("BAK", "1001"))
                .isEqualTo("BAK-1001");
    }

    @Test
    void importUsersFromBranch_faceAlreadyOnSiblingDevice_doesNotFailImport() throws Exception {
        Branch branch = branch(10L, "Baku", "BAK");
        when(branchRepository.findById(10L)).thenReturn(Optional.of(branch));

        DeviceConfig entry = device(1L, "101", "Entry", "10.0.0.1", 10L);
        DeviceConfig exit = device(2L, "102", "Exit", "10.0.0.2", 10L);
        when(deviceConfigRepository.findByBranchId(10L)).thenReturn(List.of(entry, exit));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [{"employeeNo":"1001","name":"Ali Valiyev","gender":"male","beginTime":"2024-01-01T00:00:00"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/users/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/101/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/faces/from-device"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));

        when(employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(eq(1L), anyString()))
                .thenReturn(Optional.empty());
        when(employeeRepository.findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(eq(1L), eq(10L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(eq(1L), anyString()))
                .thenReturn(List.of());
        when(employeeRepository.findByTenantIdAndEmployeeIdIn(eq(1L), any()))
                .thenReturn(List.of());
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            e.setId(77L);
            return e;
        });
        when(employeeRepository.findById(77L)).thenAnswer(inv -> {
            Employee e = new Employee();
            e.setId(77L);
            e.setEmployeeId("BAK-1001");
            e.setDeviceEmployeeNo("1001");
            e.setFirstName("Ali");
            e.setLastName("Valiyev");
            e.setTenantId(1L);
            e.setBranchId(10L);
            return Optional.of(e);
        });
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(eq(77L), eq(1L)))
                .thenReturn(false)
                .thenReturn(true);
        when(employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(eq(77L), eq(2L)))
                .thenReturn(false);
        when(employeeFaceImageService.hasFaceImage(77L)).thenReturn(true);

        Path tempFace = Files.createTempFile("import-face-", ".jpg");
        Files.write(tempFace, new byte[]{1, 2, 3});
        when(employeeFaceImageService.getLatestFaceImage(77L))
                .thenReturn(Optional.of(new EmployeeFaceImageService.FaceImageData(tempFace, "image/jpeg")));

        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/users"),
                eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"))
                .thenReturn(ResponseEntity.ok("""
                        [{"id":5,"employeeNo":"1001"}]
                        """));
        when(restTemplate.exchange(eq("http://isapi:8081/api/devices/102/users/5/face"),
                eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT, "duplicate"));

        DeviceEmployeeImportDTO.ImportResult result = service.importUsersFromBranch(
                new DeviceEmployeeImportDTO.ImportRequest(10L, null, true));

        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getWrittenToOtherDevices()).isEqualTo(1);
        assertThat(result.getFacesOtherDeviceFailed()).isZero();
        verify(restTemplate, times(2)).exchange(eq("http://isapi:8081/api/devices/102/users"),
                eq(HttpMethod.GET), eq(null), eq(String.class));
    }

    private static Branch branch(Long id, String name, String code) {
        Branch b = new Branch();
        b.setId(id);
        b.setName(name);
        b.setCode(code);
        b.setTenantId(1L);
        return b;
    }

    private static DeviceConfig device(Long id, String isapiId, String name, String ip, Long branchId) {
        DeviceConfig d = new DeviceConfig();
        d.setId(id);
        d.setDeviceId(isapiId);
        d.setDeviceName(name);
        d.setDeviceIp(ip);
        d.setDevicePort(80);
        d.setUsername("admin");
        d.setPasswordEncrypted("ENC:x");
        d.setBranchId(branchId);
        d.setTenantId(1L);
        return d;
    }
}
