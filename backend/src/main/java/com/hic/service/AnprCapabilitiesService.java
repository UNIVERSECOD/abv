package com.hic.service;

import com.hic.dto.AnprCapabilitiesDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.DeviceAddress;
import com.hic.util.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class AnprCapabilitiesService {
    private final DeviceConfigRepository devices;
    private final DeviceTargetPolicy targets;
    private final RestTemplate bridge;
    private final String base;

    public AnprCapabilitiesService(DeviceConfigRepository devices, DeviceTargetPolicy targets,
            @Qualifier("anprCapabilitiesRestTemplate") RestTemplate bridge, @Value("${isapi.base-url}") String base) {
        this.devices = devices;
        this.targets = targets;
        this.bridge = bridge;
        this.base = base.replaceAll("/+$", "");
    }

    record Target(String endpoint, String cameraName) {}

    public AnprCapabilitiesDTO check(long id) {
        Long tenant = TenantContext.getTenantId();
        var camera = devices.findById(id).filter(row -> tenant != null && tenant.equals(row.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", id));
        if (!camera.isAnpr()) throw new BadRequestException("ANPR kamera seçin.");
        String endpoint;
        long bridgeId;
        try {
            bridgeId = Long.parseLong(camera.getDeviceId());
            if (bridgeId <= 0) throw new IllegalArgumentException();
            endpoint = DeviceAddress.normalize(camera.getDeviceIp(), camera.getDevicePort());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return AnprCapabilitiesDTO.unavailable("Kameranın ISAPI bağlantısı etibarlı deyil.");
        }
        targets.requireAllowed(endpoint, camera.getBranchId());
        var headers = new HttpHeaders();
        headers.set("X-Allowed-Device-Ids", Long.toString(bridgeId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            var result = bridge.postForObject(base + "/api/anpr/cameras/" + bridgeId + "/capabilities",
                    new HttpEntity<>(new Target(endpoint, camera.getDeviceName()), headers), AnprCapabilitiesDTO.class);
            if (result != null && result.checkedAt() != null && valid(result.plateWrite())
                    && valid(result.ownerName()) && valid(result.archiveRead())) return result;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                return AnprCapabilitiesDTO.unavailable("Kamera dəyişib və ya yoxlama artıq davam edir. Siyahını yeniləyib təkrar yoxlayın.");
            }
            if (ex.getStatusCode().value() == 404) {
                return AnprCapabilitiesDTO.unavailable("ISAPI xidmətində kamera və ya imkan yoxlaması tapılmadı. Xidmətin versiyasını yoxlayın.");
            }
        } catch (RestClientException ignored) {
            // Never forward raw bridge/device responses or credentials to the UI.
        }
        return AnprCapabilitiesDTO.unavailable("ISAPI xidmətindən yoxlama nəticəsi alınmadı. Bağlantını yoxlayıb yenidən cəhd edin.");
    }

    private static boolean valid(AnprCapabilitiesDTO.Feature feature) {
        return feature != null && feature.status() != null && List.of("SUPPORTED", "UNSUPPORTED", "UNKNOWN").contains(feature.status())
                && feature.reason() != null && !feature.reason().isBlank();
    }
}
