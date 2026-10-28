package com.hic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevicePictureSecurityTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void cacheHitCannotBypassDeviceOwnership() {
        var devices = mock(DeviceConfigRepository.class);
        var cache = new DevicePictureCache();
        var http = mock(org.springframework.web.client.RestTemplate.class);
        cache.put("http://device/picture", new byte[]{1});
        var foreign = new DeviceConfig(); foreign.setId(1L); foreign.setTenantId(2L);
        when(devices.findById(1L)).thenReturn(Optional.of(foreign));
        TenantContext.setTenantId(1L);
        var service = new DeviceLogSearchService(devices, cache, new ObjectMapper(), http);
        assertThatThrownBy(() -> service.fetchPicture("http://device/picture", 1L))
                .isInstanceOf(com.hic.exception.ResourceNotFoundException.class);
        verifyNoInteractions(http);
    }

    @Test
    void arbitraryOriginIsRejectedBeforeHttpRequest() {
        var devices = mock(DeviceConfigRepository.class);
        var http = mock(org.springframework.web.client.RestTemplate.class);
        var own = new DeviceConfig(); own.setId(1L); own.setTenantId(1L); own.setDeviceId("901"); own.setDeviceIp("192.0.2.10");
        when(devices.findById(1L)).thenReturn(Optional.of(own));
        when(devices.findByTenantId(1L)).thenReturn(java.util.List.of(own));
        TenantContext.setTenantId(1L);
        var service = new DeviceLogSearchService(devices, new DevicePictureCache(), new ObjectMapper(), http);
        for (String url : java.util.List.of("http://127.0.0.1/private", "http://192.0.2.10:8080/p", "http://user@192.0.2.10/p", "file:///etc/passwd")) {
            assertThatThrownBy(() -> service.fetchPicture(url, 1L)).isInstanceOf(com.hic.exception.BadRequestException.class);
        }
        verifyNoInteractions(http);
    }
}
