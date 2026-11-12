package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.AnprCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnprCapabilitiesControllerTest {
    @Test
    void validatesExactScopeEndpointAndNameBeforeAnyCameraRequest() {
        var devices = mock(DeviceRepository.class);
        var probe = mock(AnprCapabilities.class);
        var controller = new AnprCapabilitiesController(devices, probe);
        ReflectionTestUtils.setField(controller, "apiKey", "fixture-only");
        var camera = new DeviceEntity();
        camera.setAnpr(true);
        camera.setIp("192.0.2.10:8080");
        camera.setName("Fixture camera");
        when(devices.findById(103L)).thenReturn(Optional.of(camera));
        var target = new AnprCapabilitiesController.Target("192.0.2.10:8080", "Fixture camera");
        assertThatThrownBy(() -> controller.check(103, "12", target)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.check(103, "103", new AnprCapabilitiesController.Target("192.0.2.11:8080", "Fixture camera")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.check(103, "103", new AnprCapabilitiesController.Target("192.0.2.10:8080", "Another camera")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(probe);
        controller.check(103, "103", target);
        verify(probe).check(camera);
        verify(devices, never()).save(any());
    }

    @Test
    void refusesProbeWithoutConfiguredBridgeKey() {
        var devices = mock(DeviceRepository.class);
        var probe = mock(AnprCapabilities.class);
        var controller = new AnprCapabilitiesController(devices, probe);
        assertThatThrownBy(() -> controller.check(1, "1", new AnprCapabilitiesController.Target("192.0.2.10:80", "Fixture")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(devices, probe);
    }
}
