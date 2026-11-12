package com.hic.service;

import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DeviceAreaServiceTest {
    @Mock DeviceConfigRepository devices;
    @Mock BranchRepository branches;
    @Mock EmployeeAreaAssignmentService assignments;
    @Mock private DeviceTargetPolicy targetPolicy;

    @InjectMocks DeviceAreaService service;
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void movesLocallyAndReconcilesMembershipWithoutChangingTerminalIdentity() {
        TenantContext.setTenantId(1L);
        DeviceConfig d = new DeviceConfig(); d.setId(5L); d.setTenantId(1L); d.setDeviceId("900"); d.setBranchId(1L); d.setDoorId(8L); d.setDoorRole("ENTRY");
        when(devices.findById(5L)).thenReturn(Optional.of(d));
        when(branches.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(new Branch()));
        service.assign(5L, 2L);
        assertThat(d.getBranchId()).isEqualTo(2L);
        assertThat(d.getDoorId()).isNull();
        assertThat(d.getDoorRole()).isEqualTo("ENTRY");
        assertThat(d.getDeviceId()).isEqualTo("900");
        verify(assignments).reconcileDeviceAreaAccess(d);
    }
    @Test void rejectsAnotherTenantsDeviceBeforeChangingAnything() {
        TenantContext.setTenantId(1L);
        DeviceConfig d = new DeviceConfig(); d.setId(5L); d.setTenantId(2L);
        when(devices.findById(5L)).thenReturn(Optional.of(d));
        assertThatThrownBy(() -> service.assign(5L, null)).isInstanceOf(com.hic.exception.ResourceNotFoundException.class);
        verifyNoInteractions(assignments, branches);
        verify(devices, never()).save(any());
    }
    @Test void movingCombinedDeviceStartsNewAreaBoundaryWithoutChangingRole() {
        TenantContext.setTenantId(1L);
        DeviceConfig d = new DeviceConfig(); d.setId(5L); d.setTenantId(1L);
        d.setBranchId(1L); d.setDoorRole("BOTH");
        when(devices.findById(5L)).thenReturn(Optional.of(d));
        when(branches.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(new Branch()));
        service.assign(5L, 2L);
        assertThat(d.getAttendanceRoleSince()).isNotNull();
        assertThat(d.getDoorRole()).isEqualTo("BOTH");
        assertThat(d.getBranchId()).isEqualTo(2L);
    }
    @Test void rejectsAnotherTenantsArea() {
        TenantContext.setTenantId(1L);
        DeviceConfig d = new DeviceConfig(); d.setId(5L); d.setTenantId(1L);
        when(devices.findById(5L)).thenReturn(Optional.of(d));
        when(branches.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(5L, 2L)).isInstanceOf(com.hic.exception.ResourceNotFoundException.class);
        verifyNoInteractions(assignments);
    }
}
