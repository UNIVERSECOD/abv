package com.hic.service;

import com.hic.exception.ResourceNotFoundException;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnprCapabilitiesServiceTest {
    private final DeviceConfigRepository devices = mock(DeviceConfigRepository.class);
    private final DeviceTargetPolicy targets = mock(DeviceTargetPolicy.class);
    private final RestTemplate bridge = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(bridge).build();
    private final AnprCapabilitiesService service = new AnprCapabilitiesService(devices, targets, bridge, "http://isapi/");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void usesOwnedBridgeIdAndCanonicalEndpointAndReturnsSeparateResults() {
        camera();
        server.expect(requestTo("http://isapi/api/anpr/cameras/103/capabilities"))
                .andExpect(header("X-Allowed-Device-Ids", "103"))
                .andExpect(jsonPath("$.endpoint").value("192.0.2.10:8080"))
                .andExpect(jsonPath("$.cameraName").value("Test camera"))
                .andRespond(withSuccess("""
                        {"checkedAt":"2026-11-10T00:00:00Z","model":"fixture","firmware":"fixture",
                          "plateWrite":{"status":"UNSUPPORTED","reason":"unsupported fixture"},
                          "ownerName":{"status":"UNKNOWN","reason":"read-only check"},
                          "archiveRead":{"status":"SUPPORTED","reason":"empty archive"}}
                        """, MediaType.APPLICATION_JSON));
        var report = service.check(12);
        assertThat(report.plateWrite().status()).isEqualTo("UNSUPPORTED");
        assertThat(report.archiveRead().status()).isEqualTo("SUPPORTED");
        verify(targets).requireAllowed("192.0.2.10:8080", 5L);
        server.verify();
    }

    @Test
    void anotherTenantCannotContactTheBridge() {
        camera();
        TenantContext.setTenantId(2L);
        assertThatThrownBy(() -> service.check(12)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(targets);
        server.verify();
    }

    @Test
    void bridgeFailureDoesNotMasqueradeAsUnsupportedCameraOrLeakResponse() {
        camera();
        server.expect(requestTo("http://isapi/api/anpr/cameras/103/capabilities"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("private upstream contents"));
        var report = service.check(12);
        assertThat(report.plateWrite().status()).isEqualTo("UNKNOWN");
        assertThat(report.archiveRead().reason()).doesNotContain("private", "upstream");
        verify(devices, never()).save(any());
        server.verify();
    }

    @Test
    void rejectsIncompleteBridgeResponse() {
        camera();
        server.expect(requestTo("http://isapi/api/anpr/cameras/103/capabilities"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(service.check(12).archiveRead().status()).isEqualTo("UNKNOWN");
        server.verify();
    }

    private void camera() {
        TenantContext.setTenantId(1L);
        var camera = new DeviceConfig();
        camera.setId(12L);
        camera.setTenantId(1L);
        camera.setBranchId(5L);
        camera.setDeviceId("103");
        camera.setDeviceIp("192.0.2.10");
        camera.setDevicePort(8080);
        camera.setDeviceName("Test camera");
        camera.setAnpr(true);
        when(devices.findById(12L)).thenReturn(Optional.of(camera));
    }
}
