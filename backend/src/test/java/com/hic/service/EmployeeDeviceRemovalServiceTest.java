package com.hic.service;

import com.hic.dto.DeviceSyncDTO;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.AppTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmployeeDeviceRemovalServiceTest {
    EmployeeRepository employees = mock(EmployeeRepository.class);
    DeviceConfigRepository devices = mock(DeviceConfigRepository.class);
    EmployeeDeviceRemovalJobRepository jobs = mock(EmployeeDeviceRemovalJobRepository.class);
    EmployeeRemovalDeviceClient client = mock(EmployeeRemovalDeviceClient.class);
    EmployeeVehicleService vehicles = mock(EmployeeVehicleService.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    List<EmployeeDeviceRemovalJob> stored = new ArrayList<>();
    Employee employee = new Employee();
    DeviceConfig device = new DeviceConfig();
    EmployeeDeviceRemovalService service;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new EmployeeDeviceRemovalService(employees, devices, jobs, client, new TransactionTemplate(manager), vehicles);
        employee.setId(4L);
        employee.setTenantId(1L);
        employee.setEmployeeId("EMP0004");
        employee.setDeviceEmployeeNo("400");
        device.setId(10L);
        device.setTenantId(1L);
        device.setDeviceId("101");
        device.setDeviceIp("192.0.2.10");
        device.setDeviceName("Test terminal");
        when(employees.lockForTermination(1L, 4L)).thenReturn(Optional.of(employee));
        when(employees.findByTenantIdAndId(1L, 4L)).thenReturn(Optional.of(employee));
        when(devices.findById(10L)).thenReturn(Optional.of(device));
        when(jobs.findByTenantIdAndEmployeeIdOrderById(1L, 4L)).thenAnswer(i -> List.copyOf(stored));
        when(jobs.save(any())).thenAnswer(i -> {
            EmployeeDeviceRemovalJob job = i.getArgument(0);
            if (job.getId() == null) { job.setId((long) stored.size() + 1); stored.add(job); }
            return job;
        });
        when(jobs.lockJob(eq(1L), anyLong())).thenAnswer(i -> stored.stream()
                .filter(j -> j.getId().equals(i.getArgument(1))).findFirst());
        DeviceSyncDTO.IsapiDeviceResponse bridge = new DeviceSyncDTO.IsapiDeviceResponse();
        bridge.setId(101L);
        bridge.setIp(device.getDeviceIp());
        bridge.setName(device.getDeviceName());
        when(client.device(101L)).thenReturn(bridge);
        when(client.absent(anyLong(), anyString())).thenReturn(true);
        online(false);
    }

    void online(boolean online) {
        when(client.status(101L)).thenReturn(
                new DeviceSyncDTO.DeviceStatusDTO(101L, online, online ? 200 : 503, "status"));
    }

    @Test
    void offlineJobSurvivesAndCompletesOnLaterWorkerPass() {
        var first = service.terminate(employee, Set.of(10L));
        assertThat(first.getFailedDevices()).isEqualTo(1);
        assertThat(employee.getEmploymentStatus()).isEqualTo(Employee.EmploymentStatus.TERMINATED);
        verify(vehicles).retireAll(1L, 4L);
        verify(client, never()).delete(anyLong(), anyString());
        var job = stored.get(0);
        assertThat(job.isCompleted()).isFalse();
        assertThat(job.getNextAttemptAt()).isAfter(AppTimeZone.now());
        online(true);
        job.setNextAttemptAt(AppTimeZone.now().minusMinutes(1));
        when(jobs.findDueIds(eq(1L), any(), any())).thenReturn(List.of(job.getId()));
        // New service instance demonstrates that progress does not depend on an in-memory queue.
        new EmployeeDeviceRemovalService(employees, devices, jobs, client,
                new TransactionTemplate(manager), vehicles).processDue(1L);
        assertThat(job.isCompleted()).isTrue();
        assertThat(job.getLastError()).isNull();
        assertThat(service.result(1L, 4L).getFailedDevices()).isZero();
        verify(client).delete(101L, "400");
    }

    @Test
    void repeatedTerminationDoesNotDuplicateJobsOrRepeatCompletedDelete() {
        online(true);
        service.terminate(employee, Set.of(10L));
        service.terminate(employee, Set.of(10L));
        assertThat(stored).hasSize(1);
        verify(client, times(1)).delete(101L, "400");
        var order = inOrder(employees, manager, client);
        order.verify(employees).saveAndFlush(employee);
        order.verify(manager).commit(any());
        order.verify(client).delete(101L, "400");
    }

    @Test
    void noDevicesCompletesImmediatelyWithoutDeviceCalls() {
        var result = service.terminate(employee, Set.of());
        assertThat(result.getTotalDevices()).isZero();
        assertThat(result.getFailedDevices()).isZero();
        assertThat(employee.getEmploymentStatus()).isEqualTo(Employee.EmploymentStatus.TERMINATED);
        verifyNoInteractions(client);
    }

    @Test
    void changedDeviceIdentityBlocksDeletionAndKeepsPending() {
        service.terminate(employee, Set.of(10L));
        device.setDeviceIp("192.0.2.99");
        online(true);
        service.attempt(1L, stored.get(0).getId(), true);
        assertThat(stored.get(0).isCompleted()).isFalse();
        assertThat(stored.get(0).getLastError()).contains("dəyişib");
        verify(client, never()).delete(anyLong(), anyString());
    }

    @Test
    void otherTenantCannotProcessJob() {
        service.terminate(employee, Set.of(10L));
        service.attempt(2L, stored.get(0).getId(), true);
        verify(client, never()).delete(anyLong(), anyString());
        assertThat(stored.get(0).getAttempts()).isEqualTo(1);
    }

    @Test
    void deviceFailureRemainsRetryableAndDoesNotStoreRawResponse() {
        online(true);
        doThrow(new RuntimeException("sensitive upstream response"))
                .when(client).delete(anyLong(), anyString());
        var result = service.terminate(employee, Set.of(10L));
        assertThat(result.getFailedDevices()).isEqualTo(1);
        assertThat(stored.get(0).getLastError()).doesNotContain("sensitive");
        assertThat(stored.get(0).getNextAttemptAt()).isAfter(AppTimeZone.now());
    }

    @Test
    void acceptedDeleteIsNotCompletionUntilLiveReadbackConfirmsAbsence() {
        online(true);
        when(client.absent(101L, "400")).thenReturn(false, true);
        var first = service.terminate(employee, Set.of(10L));
        assertThat(first.getFailedDevices()).isEqualTo(1);
        assertThat(stored.get(0).isCompleted()).isFalse();
        service.attempt(1L, stored.get(0).getId(), true);
        assertThat(stored.get(0).isCompleted()).isTrue();
    }

    @Test
    void unavailableReadbackKeepsJobPending() {
        online(true);
        when(client.absent(101L, "400")).thenThrow(new RuntimeException("transport unavailable"));
        assertThat(service.terminate(employee, Set.of(10L)).getFailedDevices()).isEqualTo(1);
        assertThat(stored.get(0).isCompleted()).isFalse();
    }

    @Test
    void oneOfflineDeviceDoesNotPreventOtherDeviceDeletion() {
        DeviceConfig other = new DeviceConfig();
        other.setId(11L);
        other.setTenantId(1L);
        other.setDeviceId("102");
        other.setDeviceIp("192.0.2.11");
        other.setDeviceName("Other terminal");
        when(devices.findById(11L)).thenReturn(Optional.of(other));
        DeviceSyncDTO.IsapiDeviceResponse bridge = new DeviceSyncDTO.IsapiDeviceResponse();
        bridge.setId(102L);
        bridge.setIp(other.getDeviceIp());
        bridge.setName(other.getDeviceName());
        when(client.device(102L)).thenReturn(bridge);
        when(client.status(102L)).thenReturn(new DeviceSyncDTO.DeviceStatusDTO(102L, true, 200, "OK"));

        var result = service.terminate(employee, new LinkedHashSet<>(List.of(10L, 11L)));

        assertThat(result.getTotalDevices()).isEqualTo(2);
        assertThat(result.getRemovedDevices()).isEqualTo(1);
        assertThat(result.getFailedDevices()).isEqualTo(1);
        verify(client, never()).delete(eq(101L), anyString());
        verify(client).delete(102L, "400");
    }
}
