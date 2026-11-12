package com.hic.service;

import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceTargetPolicyTest {
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void scopesConfiguredTargetsByTenantAreaAndPort() {
        var repository = mock(DeviceConfigRepository.class);
        var policy = new DeviceTargetPolicy(repository);
        ReflectionTestUtils.setField(policy, "allowedTargets", "1/5=10.0.0.10:8080;2/5=10.0.0.20");
        TenantContext.setTenantId(1L);
        policy.requireAllowed("10.0.0.10:8080", 5L);
        assertThatThrownBy(() -> policy.requireAllowed("10.0.0.10:80", 5L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.requireAllowed("10.0.0.10:8080", 6L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.requireAllowed("10.0.0.20", 5L)).isInstanceOf(RuntimeException.class);
        verify(repository, never()).findByTenantId(2L);
    }
    @Test void preservesOnlyExistingTargetsInTheirOriginalArea() {
        var repository = mock(DeviceConfigRepository.class);
        var policy = new DeviceTargetPolicy(repository);
        DeviceConfig device = new DeviceConfig(); device.setDeviceIp("192.168.1.10"); device.setBranchId(5L);
        TenantContext.setTenantId(1L);
        when(repository.findByTenantId(1L)).thenReturn(List.of(device));
        policy.requireAllowed("192.168.1.10:80", 5L);
        assertThatThrownBy(() -> policy.requireAllowed("192.168.1.10", 6L)).isInstanceOf(RuntimeException.class);
        ReflectionTestUtils.setField(policy, "allowedTargets", "1/5=10.0.0.10");
        assertThatThrownBy(() -> policy.requireAllowed("192.168.1.10", 5L)).isInstanceOf(RuntimeException.class);
    }

    @Test void respectsLegacySeparatePortAndDoesNotAuthorizeTheDefaultPortInstead() {
        var repository = mock(DeviceConfigRepository.class);
        var policy = new DeviceTargetPolicy(repository);
        var camera = new DeviceConfig();
        camera.setDeviceIp("192.0.2.10");
        camera.setDevicePort(8080);
        camera.setBranchId(5L);
        TenantContext.setTenantId(1L);
        when(repository.findByTenantId(1L)).thenReturn(List.of(camera));
        policy.requireAllowed("192.0.2.10:8080", 5L);
        assertThatThrownBy(() -> policy.requireAllowed("192.0.2.10:80", 5L)).isInstanceOf(RuntimeException.class);
        camera.setDeviceIp("192.0.2.10:8081");
        policy.requireAllowed("192.0.2.10:8081", 5L);
        assertThatThrownBy(() -> policy.requireAllowed("192.0.2.10:8080", 5L)).isInstanceOf(RuntimeException.class);
    }
}
