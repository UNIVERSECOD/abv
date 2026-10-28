package com.hic.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.dto.DeviceLogSearchDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.DeviceConfig;
import com.hic.util.TenantContext;
import java.net.URI;
import com.hic.repository.DeviceConfigRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Proxies Hikvision ISAPI AcsEvent search requests through the ISAPI integration
 * service, which already stores device credentials and authenticates with Digest.
 *
 * <p>Search uses {@code major=0, minor=0} to match the Hikvision device web UI,
 * returning all event types. Only events that include a {@code pictureURL} from
 * the device are marked as having a photo.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceLogSearchService {

    /** 0 = all event categories (matches Hikvision device Search UI). */
    private static final int MAJOR_ALL = 0;
    private static final int MINOR_ALL = 0;

    private final DeviceConfigRepository deviceConfigRepository;
    private final DevicePictureCache devicePictureCache;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${isapi.base-url}")
    private String isapiBaseUrl;

    public DeviceLogSearchDTO.SearchResultDTO search(
            Long deviceConfigId,
            String employeeId,
            String name,
            String cardNo,
            String startTime,
            String endTime,
            int page,
            int pageSize
    ) {
        DeviceConfig device = deviceConfigRepository.findById(deviceConfigId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", deviceConfigId));
        requireTenant(device);

        Long isapiDeviceId = resolveIsapiDeviceId(device);
        int searchResultPosition = page * pageSize;

        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("searchID", UUID.randomUUID().toString());
        cond.put("searchResultPosition", searchResultPosition);
        cond.put("maxResults", pageSize);
        cond.put("major", MAJOR_ALL);
        cond.put("minor", MINOR_ALL);
        cond.put("picEnable", true);
        cond.put("startTime", startTime != null ? startTime : "");
        cond.put("endTime", endTime != null ? endTime : "");
        if (StringUtils.hasText(employeeId)) {
            cond.put("employeeNoString", employeeId.trim());
        }
        if (StringUtils.hasText(cardNo)) {
            cond.put("cardNo", cardNo.trim());
        }

        Map<String, Object> requestBody = Map.of("AcsEventCond", cond);
        String url = UriComponentsBuilder
                .fromHttpUrl(trimTrailingSlash(isapiBaseUrl) + "/api/acs-events/search")
                .queryParam("deviceId", isapiDeviceId)
                .toUriString();

        log.info("Device log search via ISAPI: deviceConfigId={}, isapiDeviceId={}, page={}, pageSize={}",
                deviceConfigId, isapiDeviceId, page, pageSize);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            IsapiAcsEventSearchResponse isapiResponse =
                    objectMapper.readValue(response.getBody(), IsapiAcsEventSearchResponse.class);

            List<IsapiAcsEventItem> events = isapiResponse.getEvents() != null
                    ? isapiResponse.getEvents()
                    : Collections.emptyList();

            List<DeviceLogSearchDTO.EventEntryDTO> items = events.stream()
                    .filter(event -> !StringUtils.hasText(name) ||
                            (event.getName() != null &&
                             event.getName().toLowerCase().contains(name.trim().toLowerCase())))
                    .map(this::toDTO)
                    .collect(Collectors.toList());

            prefetchPictures(deviceConfigId, items);

            return DeviceLogSearchDTO.SearchResultDTO.builder()
                    .items(items)
                    .totalMatches(isapiResponse.getTotalMatches())
                    .numOfMatches(isapiResponse.getNumOfMatches())
                    .page(page)
                    .pageSize(pageSize)
                    .responseStatus(isapiResponse.getResponseStatusStrg())
                    .build();

        } catch (HttpStatusCodeException ex) {
            throw mapUpstreamException(ex);
        } catch (ResourceAccessException ex) {
            log.error("ISAPI service unavailable for device log search: {}", ex.getMessage());
            throw new RuntimeException("Could not reach the ISAPI service. Check that it is running.", ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error during device log search for deviceConfigId={}: {}", deviceConfigId, ex.getMessage(), ex);
            throw new RuntimeException("Unexpected error: " + ex.getMessage(), ex);
        }
    }

    public byte[] fetchPicture(String pictureUrl, Long deviceConfigId) {
        DeviceConfig device = deviceConfigRepository.findById(deviceConfigId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", deviceConfigId));
        requireTenant(device);
        Long isapiDeviceId = resolveIsapiDeviceIdForPicture(device, pictureUrl);
        String cacheKey = device.getTenantId() + ":" + deviceConfigId + ":" + pictureUrl;
        Optional<byte[]> cached = devicePictureCache.get(cacheKey);
        if (cached.isPresent()) return cached.get();
        String url = UriComponentsBuilder
                .fromHttpUrl(trimTrailingSlash(isapiBaseUrl) + "/api/acs-events/picture")
                .queryParam("deviceId", isapiDeviceId)
                .toUriString();

        log.info("Fetching picture via ISAPI: deviceConfigId={}, isapiDeviceId={}", deviceConfigId, isapiDeviceId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of("url", pictureUrl), headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new RuntimeException("Picture is no longer available on the device.");
            }
            devicePictureCache.put(cacheKey, body);
            return body;
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new RuntimeException("Picture is no longer available on the device.");
            }
            throw mapUpstreamException(ex);
        } catch (ResourceAccessException ex) {
            log.error("ISAPI service unavailable for picture fetch: {}", ex.getMessage());
            throw new RuntimeException("Could not reach the ISAPI service to fetch the picture.", ex);
        }
    }

    /**
     * Picks the ISAPI device whose IP matches the picture URL host so credentials
     * are applied to the device that actually stores the image file.
     */
    private void requireTenant(DeviceConfig device) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || !tenantId.equals(device.getTenantId())) {
            throw new ResourceNotFoundException("DeviceConfig", device.getId());
        }
    }

    private Long resolveIsapiDeviceIdForPicture(DeviceConfig selectedDevice, String pictureUrl) {
        try {
            URI uri = URI.create(pictureUrl);
            if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            for (DeviceConfig candidate : deviceConfigRepository.findByTenantId(selectedDevice.getTenantId())) {
                URI origin = URI.create("http://" + candidate.getDeviceIp());
                int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
                int configuredPort = origin.getPort() < 0 ? 80 : origin.getPort();
                if (uri.getHost().equalsIgnoreCase(origin.getHost()) && "http".equalsIgnoreCase(uri.getScheme()) && port == configuredPort) {
                    return resolveIsapiDeviceId(candidate);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Reject malformed URLs without disclosing capability tokens in errors.
        }
        throw new BadRequestException("Şəkil ünvanı şirkətin qeydiyyatlı cihazına aid deyil");
    }

    /** Fetch pictures in the background right after search while {@code @WEB} tokens are fresh. */
    private void prefetchPictures(Long deviceConfigId, List<DeviceLogSearchDTO.EventEntryDTO> items) {
        List<String> urls = items.stream()
                .map(DeviceLogSearchDTO.EventEntryDTO::getPictureURL)
                .filter(StringUtils::hasText)
                .filter(url -> devicePictureCache.get(TenantContext.getTenantId() + ":" + deviceConfigId + ":" + url).isEmpty())
                .distinct()
                .toList();

        if (urls.isEmpty()) {
            return;
        }

        Long tenantId = TenantContext.getTenantId();
        CompletableFuture.runAsync(() -> {
            TenantContext.setTenantId(tenantId);
            try {
                for (String url : urls) {
                    try {
                        fetchPicture(url, deviceConfigId);
                    } catch (RuntimeException ex) {
                        log.debug("Picture prefetch skipped for device {}", deviceConfigId);
                    }
                }
            } finally {
                TenantContext.clear();
            }
        });
    }

    private Long resolveIsapiDeviceId(DeviceConfig device) {
        if (!StringUtils.hasText(device.getDeviceId())) {
            throw new BadRequestException("Device is not linked to an ISAPI device record");
        }
        try {
            return Long.valueOf(device.getDeviceId().trim());
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Invalid ISAPI device id on device config: " + device.getDeviceId());
        }
    }

    private RuntimeException mapUpstreamException(HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString();
        if (StringUtils.hasText(body)) {
            if (body.contains("Authentication failed (401)")) {
                return new RuntimeException("Authentication failed (401): wrong username or password for device.");
            }
            try {
                Map<?, ?> error = objectMapper.readValue(body, Map.class);
                Object message = error.get("message");
                if (message instanceof String text && StringUtils.hasText(text)) {
                    return new RuntimeException(text);
                }
            } catch (Exception ignored) {
                // fall through to generic message
            }
        }
        if (ex.getStatusCode().value() == 401) {
            return new RuntimeException("Authentication failed (401): wrong username or password for device.");
        }
        String message = StringUtils.hasText(body) ? body : ex.getMessage();
        return new RuntimeException("Device request failed: " + message);
    }

    private DeviceLogSearchDTO.EventEntryDTO toDTO(IsapiAcsEventItem event) {
        boolean hasPicture = StringUtils.hasText(event.getPictureURL());
        return DeviceLogSearchDTO.EventEntryDTO.builder()
                .employeeId(event.getEmployeeNoString())
                .name(event.getName())
                .cardNo(event.getCardNo())
                .eventDescription(resolveEventDescription(event.getMajorEventType(), event.getSubEventType()))
                .time(event.getTime())
                .hasPicture(hasPicture)
                .pictureURL(hasPicture ? event.getPictureURL() : null)
                .verifyMode(event.getCurrentVerifyMode())
                .build();
    }

    private String resolveEventDescription(int major, int minor) {
        if (major == 5) {
            return switch (minor) {
                case 75 -> "Authenticated via Face / Card";
                case 21 -> "Door Locked";
                case 22 -> "Door Unlocked";
                case 32 -> "Card Reader Offline";
                case 1  -> "Verified";
                default -> "Access Control Event (" + minor + ")";
            };
        }
        if (major == 3) {
            return switch (minor) {
                case 80  -> "Device Powering On";
                case 112 -> "Local: Login";
                case 113 -> "Local: Logout";
                default -> "System Event (" + minor + ")";
            };
        }
        if (major == 0 && minor == 0) {
            return "All Events";
        }
        return "Event (major=" + major + ", minor=" + minor + ")";
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class IsapiAcsEventSearchResponse {
        private String searchID;
        private String responseStatusStrg;
        private int numOfMatches;
        private int totalMatches;
        private List<IsapiAcsEventItem> events;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class IsapiAcsEventItem {
        private long serialNo;
        private int majorEventType;
        @JsonProperty("subEventType")
        private int subEventType;
        private String time;
        private String name;
        private String employeeNoString;
        private String cardNo;
        private String pictureURL;
        private String currentVerifyMode;
        private Integer picturesNumber;
    }
}
