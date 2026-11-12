package com.hic.service;

import com.hic.dto.DeviceReconciliationDTO.*;
import com.hic.dto.DeviceSyncDTO.*;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceReconciliationServiceTest {
    @Mock DeviceConfigRepository devices;
    @Mock EmployeeRepository employees;
    @Mock EmployeeAreaRepository areas;
    @Mock EmployeeDeviceAccessRepository access;
    @Mock DeviceReconciliationClient client;
    @Mock AuditLogService audit;
    @InjectMocks DeviceReconciliationService service;
    DeviceConfig device;
    List<Employee> all = new ArrayList<>();
    List<EmployeeArea> members = new ArrayList<>();
    List<EmployeeDeviceAccess> manual = new ArrayList<>();
    List<DeviceReconciliationClient.Person> physical = new ArrayList<>();

    @BeforeEach void setup() {
        TenantContext.setTenantId(1L); TenantContext.setUserId(8L);
        device = new DeviceConfig(); device.setId(5L); device.setTenantId(1L); device.setDeviceId("900");
        device.setDeviceIp("127.0.0.25"); device.setDeviceName("Mock entry"); device.setBranchId(2L); device.setStatus("ACTIVE");
        lenient().when(devices.findById(5L)).thenReturn(Optional.of(device));
        lenient().when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(1L)).thenReturn(all);
        lenient().when(areas.findByBranchId(2L)).thenReturn(members);
        lenient().when(access.findByDeviceConfigId(5L)).thenReturn(manual);
        lenient().when(client.device(900L)).thenReturn(new IsapiDeviceResponse(900L, "127.0.0.25", "mock", "Mock entry", true, true, true, null, false));
        lenient().when(client.status(900L)).thenReturn(new DeviceStatusDTO(900L, true, 200, null));
        lenient().when(client.snapshot(900L)).thenAnswer(i -> physical.stream().sorted(Comparator.comparing(DeviceReconciliationClient.Person::employeeNo)).toList());
    }
    @AfterEach void clear() { TenantContext.clear(); }
    Employee employee(long id, boolean area, boolean individually, boolean onDevice) {
        Employee e = new Employee(); e.setId(id); e.setTenantId(1L); e.setEmployeeId("HR" + id);
        e.setDeviceEmployeeNo("T" + id); e.setFirstName("Test" + id); e.setLastName("Employee"); all.add(e);
        if (area) { EmployeeArea a = new EmployeeArea(); a.setTenantId(1L); a.setEmployeeId(id); members.add(a); }
        if (individually) { EmployeeDeviceAccess a = new EmployeeDeviceAccess(); a.setTenantId(1L); a.setEmployeeId(id); manual.add(a); }
        if (onDevice) physical.add(person(e));
        return e;
    }
    DeviceReconciliationClient.Person person(Employee e) { return new DeviceReconciliationClient.Person(e.getDeviceEmployeeNo(), DeviceReconciliationService.name(e), "normal", null, null, null); }
    ApplyRequest request(Preview p, int step) { return new ApplyRequest(p.token(), step, true); }

    @Test void previewsMoveKeepsManualAndSharedMembersAndDoesNotWrite() {
        employee(1, false, false, true); employee(2, true, false, false);
        employee(3, true, false, true); employee(4, false, true, true);
        physical.add(new DeviceReconciliationClient.Person("unknown", "Guest", "normal", null, null, null));
        Preview p = service.preview(5L);
        assertThat(p.rows()).extracting(Row::action).containsExactly("REMOVE", "KEEP", "KEEP", "UNKNOWN", "ADD");
        assertThat(p.operations()).isEqualTo(2);
        verify(client, never()).add(anyLong(), any()); verify(client, never()).remove(anyLong(), any());
        verifyNoInteractions(audit);
    }
    @Test void addsBeforeRemovingAndRepeatedStepCannotWriteTwice() throws Exception {
        employee(1, false, false, true); Employee added = employee(2, true, false, false);
        Preview p = service.preview(5L);
        when(client.add(900L, added)).thenAnswer(i -> { physical.add(person(added)); return 99L; });
        when(client.addFace(900L, 99L, added)).thenReturn("Şəkil yoxdur");
        doAnswer(i -> { physical.removeIf(x -> x.employeeNo().equals("T1")); return null; }).when(client).remove(900L, "T1");
        assertThat(service.apply(5L, request(p, 0)).finished()).isFalse();
        service.apply(5L, request(p, 0));
        Progress done = service.apply(5L, request(p, 1));
        assertThat(done.finished()).isTrue(); assertThat(done.outcomes()).extracting(Outcome::status).containsOnly("SUCCESS");
        verify(client, times(1)).add(900L, added); verify(client, times(1)).remove(900L, "T1");
        verify(employees, never()).save(any()); verify(employees, never()).delete(any());
    }
    @Test void rejectsChangedAreaAfterPreview() {
        employee(1, false, false, true); Preview p = service.preview(5L); device.setBranchId(3L);
        assertThatThrownBy(() -> service.apply(5L, request(p, 0))).hasMessageContaining("dəyişib");
        verify(client, never()).remove(anyLong(), any());
    }
    @Test void rejectsChangedDeviceListAfterPreview() {
        employee(1, false, false, true); Preview p = service.preview(5L); physical.clear();
        assertThatThrownBy(() -> service.apply(5L, request(p, 0))).hasMessageContaining("dəyişib");
        verify(client, never()).remove(anyLong(), any());
    }
    @Test void rejectsNewManualAssignmentAfterPreview() {
        employee(1, false, false, true); Preview p = service.preview(5L);
        EmployeeDeviceAccess a = new EmployeeDeviceAccess(); a.setTenantId(1L); a.setEmployeeId(1L); manual.add(a);
        assertThatThrownBy(() -> service.apply(5L, request(p, 0))).hasMessageContaining("dəyişib");
        verify(client, never()).remove(anyLong(), any());
    }
    @Test void doesNotTouchAmbiguousNumbersNameMismatchOrAdmin() {
        Employee a = employee(1, false, false, true), b = employee(2, true, false, false); b.setDeviceEmployeeNo(a.getDeviceEmployeeNo());
        employee(3, false, false, false); physical.add(new DeviceReconciliationClient.Person("T3", "Other name", "normal", null, null, null));
        Employee c = employee(4, false, false, false); physical.add(new DeviceReconciliationClient.Person("T4", DeviceReconciliationService.name(c), "administrator", null, null, null));
        Preview p = service.preview(5L); assertThat(p.operations()).isZero(); assertThat(p.rows()).extracting(Row::action).containsOnly("CONFLICT");
    }
    @Test void skipsInactiveAdditionButKeepsExistingAssignedPerson() {
        employee(1, true, false, false).setEmploymentStatus(Employee.EmploymentStatus.INACTIVE);
        employee(2, false, true, true).setEmploymentStatus(Employee.EmploymentStatus.INACTIVE);
        Preview p = service.preview(5L); assertThat(p.operations()).isZero(); assertThat(p.rows()).extracting(Row::action).containsExactly("KEEP", "SKIP");
    }
    @Test void stopsAfterFailedAdditionWithoutDeleting() {
        Employee e = employee(1, true, false, false); employee(2, false, false, true);
        Preview p = service.preview(5L); when(client.add(900L, e)).thenThrow(new IllegalStateException());
        Progress result = service.apply(5L, request(p, 0));
        assertThat(result.finished()).isTrue(); assertThat(result.outcomes().get(0).status()).isEqualTo("UNVERIFIED");
        service.apply(5L, request(p, 1)); verify(client, never()).remove(anyLong(), any());
    }
    @Test void requiresPhysicalReadbackAfterDeleteAcknowledgement() {
        employee(1, false, false, true); Preview p = service.preview(5L);
        assertThat(service.apply(5L, request(p, 0)).outcomes().get(0).status()).isEqualTo("UNVERIFIED");
    }
    @Test void faceFailureIsPartialAndStopsPendingRemovals() throws Exception {
        Employee e = employee(1, true, false, false); employee(2, false, false, true);
        Preview p = service.preview(5L);
        when(client.add(900L, e)).thenAnswer(i -> { physical.add(person(e)); return 1L; });
        when(client.addFace(900L, 1L, e)).thenThrow(new IllegalStateException());
        Progress result = service.apply(5L, request(p, 0));
        assertThat(result.finished()).isTrue(); assertThat(result.outcomes().get(0).status()).isEqualTo("WARNING");
        verify(client, never()).remove(anyLong(), any());
    }
    @Test void ignoresForeignTenantMembershipAndManualRows() {
        employee(1, true, true, true); members.get(0).setTenantId(2L); manual.get(0).setTenantId(2L);
        assertThat(service.preview(5L).rows().get(0).action()).isEqualTo("REMOVE");
    }
    @Test void statusChangeBeforeApplyPreventsReactivation() {
        Employee e = employee(1, true, false, false); Preview p = service.preview(5L);
        e.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);
        assertThatThrownBy(() -> service.apply(5L, request(p, 0))).hasMessageContaining("dəyişib");
        verify(client, never()).add(anyLong(), any());
    }
    @Test void offlineOrIdentityMismatchCannotPreview() {
        when(client.status(900L)).thenReturn(new DeviceStatusDTO(900L, false, 0, null));
        assertThatThrownBy(() -> service.preview(5L)).hasMessageContaining("təsdiqlənmədi");
        when(client.status(900L)).thenReturn(new DeviceStatusDTO(900L, true, 200, null)); device.setDeviceIp("127.0.0.26");
        assertThatThrownBy(() -> service.preview(5L)).hasMessageContaining("təsdiqlənmədi");
        verify(client, never()).snapshot(anyLong());
    }
    @Test void rejectsOtherTenantAndMissingContextBeforeDeviceCalls() {
        device.setTenantId(2L); assertThatThrownBy(() -> service.preview(5L)).isInstanceOf(com.hic.exception.ResourceNotFoundException.class);
        TenantContext.clear(); assertThatThrownBy(() -> service.preview(5L)).hasMessageContaining("sessiyası"); verifyNoInteractions(client);
    }
    @Test void confirmationBelongsToSameActorAndNeedsExplicitConsent() {
        employee(1, false, false, true); Preview p = service.preview(5L);
        assertThatThrownBy(() -> service.apply(5L, new ApplyRequest(p.token(), 0, false))).hasMessageContaining("təsdiqləyin");
        TenantContext.setUserId(9L); assertThatThrownBy(() -> service.apply(5L, request(p, 0))).hasMessageContaining("etibarlı deyil");
        verify(client, never()).remove(anyLong(), any());
    }
}
