package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.DeviceLogPictureRequest;
import com.hic.dto.DeviceLogSearchDTO;
import com.hic.exception.ResourceNotFoundException;
import com.hic.service.DeviceLogSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * REST controller for the Device Log Search feature.
 *
 * <p>All endpoints are read-only and write nothing to the database — they act
 * as a transparent proxy between the React frontend and the physical Hikvision
 * device's ISAPI interface.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/device-logs/search} — search Access Control events on
 *       a specific device using ISAPI {@code /ISAPI/AccessControl/AcsEvent}</li>
 *   <li>{@code GET /api/device-logs/picture} — proxy-download a picture from
 *       the device and return it as {@code image/jpeg} bytes</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/device-logs")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class DeviceLogSearchController {

    private final DeviceLogSearchService deviceLogSearchService;

    /**
     * Searches for Access Control events on a Hikvision device.
     *
     * @param deviceId  PK from the {@code device_configs} table (required)
     * @param employeeId optional employee number; sent as {@code employeeNoString} to device
     * @param name       optional name substring; filtered client-side in the service
     * @param cardNo     optional card number; sent to device
     * @param startTime  ISO-8601 datetime string (e.g. {@code 2026-07-08T00:00:00+04:00})
     * @param endTime    ISO-8601 datetime string
     * @param page       zero-based page index (default 0)
     * @param pageSize   results per page (default 24, maps to ISAPI {@code maxResults})
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<DeviceLogSearchDTO.SearchResultDTO>> search(
            @RequestParam                     Long    deviceId,
            @RequestParam(required = false)   String  employeeId,
            @RequestParam(required = false)   String  name,
            @RequestParam(required = false)   String  cardNo,
            @RequestParam(required = false)   String  startTime,
            @RequestParam(required = false)   String  endTime,
            @RequestParam(defaultValue = "0") int     page,
            @RequestParam(defaultValue = "24") int    pageSize
    ) {
        try {
            DeviceLogSearchDTO.SearchResultDTO result = deviceLogSearchService.search(
                    deviceId, employeeId, name, cardNo, startTime, endTime, page, pageSize
            );
            return ResponseEntity.ok(ApiResponse.success(result));

        } catch (com.hic.exception.BadRequestException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Device not found: " + e.getMessage()));

        } catch (RuntimeException e) {
            // Surface device connectivity / auth errors to the frontend as 502
            log.warn("Device log search failed for deviceId={}: {}", deviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Proxy-downloads a picture from the Hikvision device using Digest Auth and
     * streams it back to the browser as {@code image/jpeg}.
     *
     * <p>The {@code url} parameter must be the full {@code pictureURL} value
     * returned by the search endpoint — including the trailing {@code @WEB…} device
     * token — URL-encoded by the caller.
     *
     * @param url      URL-encoded pictureURL from the device
     * @param deviceId PK from the {@code device_configs} table (for credentials)
     */
    @GetMapping("/picture")
    public ResponseEntity<byte[]> getPicture(
            @RequestParam String url,
            @RequestParam Long   deviceId
    ) {
        return servePicture(url, deviceId);
    }

    /**
     * Preferred picture proxy — avoids long query strings and {@code @} encoding issues.
     */
    @PostMapping("/picture")
    public ResponseEntity<byte[]> postPicture(@RequestBody DeviceLogPictureRequest body) {
        if (body == null || body.getUrl() == null || body.getUrl().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (body.getDeviceId() == null) {
            return ResponseEntity.badRequest().build();
        }
        return servePicture(body.getUrl(), body.getDeviceId());
    }

    private ResponseEntity<byte[]> servePicture(String url, Long deviceId) {
        try {
            String decodedUrl = url.contains("%")
                    ? URLDecoder.decode(url, StandardCharsets.UTF_8)
                    : url;
            byte[] imageBytes = deviceLogSearchService.fetchPicture(decodedUrl, deviceId);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("Empty picture body for deviceId={}", deviceId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_JPEG);
            headers.setContentLength(imageBytes.length);
            headers.setCacheControl("no-store");

            return ResponseEntity.ok().headers(headers).body(imageBytes);

        } catch (com.hic.exception.BadRequestException e) {
            return ResponseEntity.badRequest().build();
        } catch (ResourceNotFoundException e) {
            log.warn("Device not found for picture proxy: deviceId={}", deviceId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        } catch (RuntimeException e) {
            log.warn("Failed to fetch picture from device deviceId={}: {}", deviceId, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("no longer available")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
