package com.hic.controller;

import com.hic.dto.DeviceLogPictureRequest;
import com.hic.exception.BadRequestException;
import com.hic.service.DeviceLogSearchService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DevicePictureControllerSecurityTest {
    @Test
    void biometricPictureIsNotStoredInBrowserOrSharedHttpCaches() {
        var service = mock(DeviceLogSearchService.class);
        var request = new DeviceLogPictureRequest();
        request.setDeviceId(1L);
        request.setUrl("http://192.0.2.10/picture");
        when(service.fetchPicture(request.getUrl(), 1L)).thenReturn(new byte[]{1, 2});
        assertThat(new DeviceLogSearchController(service).postPicture(request).getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void disallowedPictureOriginIsAClientErrorInsteadOfAnUpstreamFailure() {
        var service = mock(DeviceLogSearchService.class);
        var request = new DeviceLogPictureRequest();
        request.setDeviceId(1L);
        request.setUrl("http://127.0.0.1/private");
        when(service.fetchPicture(request.getUrl(), 1L)).thenThrow(new BadRequestException("Invalid origin"));
        assertThat(new DeviceLogSearchController(service).postPicture(request).getStatusCode().value()).isEqualTo(400);
    }
}
