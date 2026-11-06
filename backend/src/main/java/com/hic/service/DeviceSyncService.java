package com.hic.service;

import com.hic.dto.DeviceSyncDTO;
import com.hic.exception.DeviceSyncException;
import com.hic.exception.UpstreamApiException;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceSyncService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String INACTIVE_STATUS = "INACTIVE";

    private final RestTemplate restTemplate;

    @Value("${isapi.base-url}")
    private String isapiBaseUrl;

    public List<DeviceSyncDTO.DeviceConfigDTO> getAllDevices(Boolean enabled) {
        log.info("ActionLog.getAllDevices.start");
        String url = UriComponentsBuilder
                .fromHttpUrl(baseDevicesUrl())
                .queryParamIfPresent("enabled", Optional.ofNullable(enabled))
                .toUriString();
        log.info("request url: {}", url);
        List<DeviceSyncDTO.IsapiDeviceResponse> devices = exchangeForList(url, HttpMethod.GET);
        List<DeviceSyncDTO.DeviceConfigDTO> result = devices.stream().map(this::toConfigDTO).toList();
        log.info("ActionLog.getAllDevices.end");
        return result;
    }

    public DeviceSyncDTO.DeviceConfigDTO getDeviceById(Long id) {
        log.info("ActionLog.getDeviceById.start");
        DeviceSyncDTO.DeviceConfigDTO result = toConfigDTO(exchangeForObject(deviceUrl(id), HttpMethod.GET, null, DeviceSyncDTO.IsapiDeviceResponse.class));
        log.info("ActionLog.getDeviceById.end");
        return result;
    }

    public DeviceSyncDTO.DeviceConfigDTO createDevice(DeviceSyncDTO.DeviceConfigDTO dto) {
        log.info("ActionLog.createDevice.start");
        DeviceSyncDTO.IsapiDeviceUpsertRequest request = toIsapiUpsertRequest(dto);
        DeviceSyncDTO.DeviceConfigDTO result = toConfigDTO(exchangeForObject(baseDevicesUrl(), HttpMethod.POST, request, DeviceSyncDTO.IsapiDeviceResponse.class));
        log.info("ActionLog.createDevice.end");
        return result;
    }

    public DeviceSyncDTO.DeviceConfigDTO updateDevice(Long id, DeviceSyncDTO.DeviceConfigDTO dto) {
        log.info("ActionLog.updateDevice.start");
        DeviceSyncDTO.IsapiDeviceUpsertRequest request = toIsapiUpsertRequest(dto);
        DeviceSyncDTO.DeviceConfigDTO result = toConfigDTO(exchangeForObject(deviceUrl(id), HttpMethod.PUT, request, DeviceSyncDTO.IsapiDeviceResponse.class));
        log.info("ActionLog.updateDevice.end");
        return result;
    }

    public DeviceSyncDTO.DeviceConfigDTO updateEnabled(Long id, DeviceSyncDTO.DeviceEnabledDTO dto) {
        log.info("ActionLog.updateEnabled.start");
        DeviceSyncDTO.IsapiDeviceEnabledRequest request = new DeviceSyncDTO.IsapiDeviceEnabledRequest(dto.isEnabled());
        DeviceSyncDTO.DeviceConfigDTO result = toConfigDTO(exchangeForObject(deviceEnabledUrl(id), HttpMethod.PATCH, request, DeviceSyncDTO.IsapiDeviceResponse.class));
        log.info("ActionLog.updateEnabled.end");
        return result;
    }

    public void deleteDevice(Long id) {
        log.info("ActionLog.deleteDevice.start");
        exchange(deviceUrl(id), HttpMethod.DELETE, null);
        log.info("ActionLog.deleteDevice.end");
    }

    public DeviceSyncDTO.DeviceRuntimeDTO startDevice(Long id) {
        log.info("ActionLog.startDevice.start");
        DeviceSyncDTO.DeviceRuntimeDTO result = toRuntimeDTO(exchangeForObject(deviceStartUrl(id), HttpMethod.POST, null, DeviceSyncDTO.IsapiDeviceRuntimeResponse.class));
        log.info("ActionLog.startDevice.end");
        return result;
    }

    public DeviceSyncDTO.DeviceRuntimeDTO stopDevice(Long id) {
        log.info("ActionLog.stopDevice.start");
        DeviceSyncDTO.DeviceRuntimeDTO result = toRuntimeDTO(exchangeForObject(deviceStopUrl(id), HttpMethod.POST, null, DeviceSyncDTO.IsapiDeviceRuntimeResponse.class));
        log.info("ActionLog.stopDevice.end");
        return result;
    }

    public DeviceSyncDTO.DeviceStatusDTO getStatus(Long id) {
        log.info("ActionLog.getStatus.start");
        DeviceSyncDTO.IsapiDeviceStatusResponse response = exchangeForObject(deviceStatusUrl(id), HttpMethod.GET, null, DeviceSyncDTO.IsapiDeviceStatusResponse.class);
        DeviceSyncDTO.DeviceStatusDTO result = new DeviceSyncDTO.DeviceStatusDTO(response.getId(), response.isOnline(), response.getStatusCode(), response.getResponseSnippet());
        log.info("ActionLog.getStatus.end");
        return result;
    }

    public DeviceSyncDTO.SyncResultDTO syncDevice(Long id) {
        log.info("ActionLog.syncDevice.start");
        DeviceSyncDTO.SyncResultDTO result = exchangeForObject(
                deviceSyncUrl(id),
                HttpMethod.POST,
                null,
                DeviceSyncDTO.SyncResultDTO.class);
        log.info("ActionLog.syncDevice.end");
        return result;
    }

    public DeviceSyncDTO.SyncResultDTO syncDeviceNow(Long id, LocalDateTime start) {
        log.info("ActionLog.syncDeviceNow.start");
        DeviceSyncDTO.SyncResultDTO result = exchangeForObject(
                deviceSyncNowUrl(id, start),
                HttpMethod.POST,
                null,
                DeviceSyncDTO.SyncResultDTO.class);
        log.info("ActionLog.syncDeviceNow.end");
        return result;
    }

    private DeviceSyncDTO.DeviceConfigDTO toConfigDTO(DeviceSyncDTO.IsapiDeviceResponse response) {
        log.info("ActionLog.toConfigDTO.start");
        DeviceSyncDTO.DeviceConfigDTO dto = new DeviceSyncDTO.DeviceConfigDTO();
        dto.setId(response.getId());
        dto.setDeviceId(String.valueOf(response.getId()));
        dto.setDeviceName(response.getName());
        dto.setDeviceIp(response.getIp());
        dto.setUsername(response.getUsername());
        dto.setStatus(response.isEnabled() ? ACTIVE_STATUS : INACTIVE_STATUS);
        dto.setOnline(response.isOnline());
        dto.setLastSyncTime(AppTimeZone.toLocalDateTime(response.getLastSyncTime()));
        log.info("ActionLog.toConfigDTO.end");
        return dto;
    }

    private DeviceSyncDTO.DeviceRuntimeDTO toRuntimeDTO(DeviceSyncDTO.IsapiDeviceRuntimeResponse response) {
        log.info("ActionLog.toRuntimeDTO.start");
        DeviceSyncDTO.DeviceRuntimeDTO dto = new DeviceSyncDTO.DeviceRuntimeDTO();
        dto.setId(response.getId());
        dto.setEnabled(response.isEnabled());
        dto.setRunning(response.isRunning());
        dto.setStatus(response.isEnabled() ? ACTIVE_STATUS : INACTIVE_STATUS);
        log.info("ActionLog.toRuntimeDTO.end");
        return dto;
    }

    private DeviceSyncDTO.IsapiDeviceUpsertRequest toIsapiUpsertRequest(DeviceSyncDTO.DeviceConfigDTO dto) {
        log.info("ActionLog.toIsapiUpsertRequest.start");
        DeviceSyncDTO.IsapiDeviceUpsertRequest request = new DeviceSyncDTO.IsapiDeviceUpsertRequest();
        request.setIp(dto.getDeviceIp());
        request.setUsername(dto.getUsername());
        request.setPassword(StringUtils.hasText(dto.getPassword()) ? dto.getPassword() : null);
        request.setName(dto.getDeviceName());
        request.setEnabled(toEnabled(dto.getStatus()));
        log.info("ActionLog.toIsapiUpsertRequest.end");
        return request;
    }

    private Boolean toEnabled(String status) {
        log.info("ActionLog.toEnabled.start");
        Boolean result = StringUtils.hasText(status) ? ACTIVE_STATUS.equalsIgnoreCase(status) : null;
        log.info("ActionLog.toEnabled.end");
        return result;
    }

    private String baseDevicesUrl() {
        log.info("ActionLog.baseDevicesUrl.start");
        String result = trimTrailingSlash(isapiBaseUrl) + "/api/devices";
        log.info(result);
        log.info("ActionLog.baseDevicesUrl.end");
        return result;
    }

    private String deviceUrl(Long id) {
        log.info("ActionLog.deviceUrl.start");
        String result = baseDevicesUrl() + "/" + id;
        log.info("ActionLog.deviceUrl.end");
        return result;
    }

    private String deviceEnabledUrl(Long id) {
        log.info("ActionLog.deviceEnabledUrl.start");
        String result = deviceUrl(id) + "/enabled";
        log.info("ActionLog.deviceEnabledUrl.end");
        return result;
    }

    private String deviceStartUrl(Long id) {
        log.info("ActionLog.deviceStartUrl.start");
        String result = deviceUrl(id) + "/start";
        log.info("ActionLog.deviceStartUrl.end");
        return result;
    }

    private String deviceStopUrl(Long id) {
        log.info("ActionLog.deviceStopUrl.start");
        String result = deviceUrl(id) + "/stop";
        log.info("ActionLog.deviceStopUrl.end");
        return result;
    }

    private String deviceStatusUrl(Long id) {
        log.info("ActionLog.deviceStatusUrl.start");
        String result = deviceUrl(id) + "/status";
        log.info("ActionLog.deviceStatusUrl.end");
        return result;
    }

    private String deviceSyncUrl(Long id) {
        return deviceUrl(id) + "/sync";
    }

    private String deviceSyncNowUrl(Long id, LocalDateTime start) {
        return org.springframework.web.util.UriComponentsBuilder
                .fromHttpUrl(deviceUrl(id) + "/sync-now")
                .queryParamIfPresent("start", java.util.Optional.ofNullable(start)
                        .map(value -> value.atZone(AppTimeZone.ZONE).toInstant().toString()))
                .build().toUriString();
    }

    private String trimTrailingSlash(String url) {
        log.info("ActionLog.trimTrailingSlash.start");
        String result = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        log.info("ActionLog.trimTrailingSlash.end");
        return result;
    }

    private void exchange(String url, HttpMethod method, Object body) {
        log.info("ActionLog.exchange.start");
        exchangeForObject(url, method, body, Void.class);
        log.info("ActionLog.exchange.end");
    }

    private <T> T exchangeForObject(String url, HttpMethod method, Object body, Class<T> responseType) {
        log.info("ActionLog.exchangeForObject.start");
        try {
            HttpEntity<?> entity = body == null ? HttpEntity.EMPTY : new HttpEntity<>(body);
            ResponseEntity<T> response = restTemplate.exchange(url, method, entity, responseType);
            return response.getBody();
        } catch (HttpStatusCodeException ex) {
            throw new UpstreamApiException(ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            throw new DeviceSyncException("ISAPI service is unavailable");
        } finally {
            log.info("ActionLog.exchangeForObject.end");
        }
    }

    private List<DeviceSyncDTO.IsapiDeviceResponse> exchangeForList(String url, HttpMethod method) {
        log.info("ActionLog.exchangeForList.start");
        try {
            ResponseEntity<List<DeviceSyncDTO.IsapiDeviceResponse>> response = restTemplate.exchange(
                    url,
                    method,
                    HttpEntity.EMPTY,
                    new ParameterizedTypeReference<>() {
                    }
            );
            return response.getBody() == null ? Collections.emptyList() : response.getBody();
        } catch (HttpStatusCodeException ex) {
            throw new UpstreamApiException(ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            throw new DeviceSyncException("ISAPI service is unavailable");
        } finally {
            log.info("ActionLog.exchangeForList.end");
        }
    }
}
