package com.hic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IsapiProxyServiceTest {

    private IsapiProxyService service;
    private MockRestServiceServer server;
    private com.hic.repository.TenantRepository tenants;

    @Test
    void sharedDeviceUserCacheIsClosedForMultipleTenants() {
        org.mockito.Mockito.when(tenants.count()).thenReturn(2L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.forward(HttpMethod.GET,
                "/api/devices/9/users", new MockHttpServletRequest(), null))
                .isInstanceOf(com.hic.exception.BadRequestException.class);
        server.verify();
    }

    @Test
    void foreignBridgeIdIsRejectedBeforeUpstreamCall() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("deviceId", "999");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.forward(HttpMethod.GET, "/api/raw-events", request, null))
                .isInstanceOf(com.hic.exception.BadRequestException.class);
        server.verify();
    }

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        var devices = org.mockito.Mockito.mock(com.hic.repository.DeviceConfigRepository.class);
        var device = new com.hic.model.DeviceConfig();
        device.setDeviceId("9");
        var second = new com.hic.model.DeviceConfig();
        second.setDeviceId("11");
        org.mockito.Mockito.when(devices.findByTenantId(1L)).thenReturn(java.util.List.of(device, second));
        com.hic.util.TenantContext.setTenantId(1L);
        tenants = org.mockito.Mockito.mock(com.hic.repository.TenantRepository.class);
        service = new IsapiProxyService(restTemplate, devices, tenants);
        ReflectionTestUtils.setField(service, "isapiBaseUrl", "http://host.docker.internal:8080/");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenant() { com.hic.util.TenantContext.clear(); }

    @Test
    void forward_get_withQuery_passthroughsResponse() {
        server.expect(requestTo("http://host.docker.internal:8080/api/punches?limit=50&deviceId=9"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-Allowed-Device-Ids", "9,11"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":1}]", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString("limit=50&deviceId=9");
        request.addHeader("Authorization", "Bearer token");

        var response = service.forward(HttpMethod.GET, "/api/punches", request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("[{\"id\":1}]");
        server.verify();
    }

    @Test
    void forward_onUpstreamError_returnsSameStatusAndBody() {
        server.expect(requestTo("http://host.docker.internal:8080/api/devices/11"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid\"}"));

        MockHttpServletRequest request = new MockHttpServletRequest();

        var response = service.forward(HttpMethod.GET, "/api/devices/11", request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"invalid\"}");
        server.verify();
    }
}
