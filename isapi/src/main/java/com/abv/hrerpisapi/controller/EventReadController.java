package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.entity.AcsFailedAttemptEntity;
import com.abv.hrerpisapi.dao.entity.AcsRawEventEntity;
import com.abv.hrerpisapi.dao.entity.AttendancePunchEntity;
import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.AcsFailedAttemptRepository;
import com.abv.hrerpisapi.dao.repository.AcsRawEventRepository;
import com.abv.hrerpisapi.dao.repository.AttendancePunchRepository;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import com.abv.hrerpisapi.model.request.device.AcsEventSearchRequest;
import com.abv.hrerpisapi.model.response.device.AcsEventSearchResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import java.io.IOException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EventReadController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final AttendancePunchRepository attendancePunchRepository;
    private final AcsRawEventRepository acsRawEventRepository;
    private final AcsFailedAttemptRepository acsFailedAttemptRepository;
    private final DeviceRepository deviceRepository;
    private final IsapiClient isapiClient;

    @GetMapping("/punches")
    public List<PunchResponse> getPunches(
            @RequestHeader(value = "X-Allowed-Device-Ids", required = false) String scope,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String employeeNo,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedEmployeeNo = normalizeToNull(employeeNo);
        boolean hasRange = start != null || end != null;
        if (hasRange && (start == null || end == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both start and end must be provided");
        }

        Pageable pageable = hasRange
                ? PageRequest.of(validatePage(page), validateLimit(size))
                : PageRequest.of(0, validateLimit(limit));

        List<AttendancePunchEntity> punches;
        if (scope != null) {
            var ids = allowedDeviceIds(scope);
            var sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    org.springframework.data.domain.Sort.by(hasRange ? org.springframework.data.domain.Sort.Direction.ASC : org.springframework.data.domain.Sort.Direction.DESC, "punchTime"));
            punches = attendancePunchRepository.findAll((root, query, cb) -> {
                var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
                predicates.add(root.get("deviceId").in(ids));
                if (deviceId != null) predicates.add(cb.equal(root.get("deviceId"), deviceId));
                if (normalizedEmployeeNo != null) predicates.add(cb.equal(root.get("employeeNo"), normalizedEmployeeNo));
                if (hasRange) predicates.add(cb.between(root.get("punchTime"), start, end));
                return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            }, sorted).getContent();
        } else if (hasRange) {
            if (deviceId != null && normalizedEmployeeNo != null) {
                punches = attendancePunchRepository.findByDeviceIdAndEmployeeNoAndPunchTimeBetweenOrderByPunchTimeAsc(
                        deviceId, normalizedEmployeeNo, start, end, pageable);
            } else if (deviceId != null) {
                punches = attendancePunchRepository.findByDeviceIdAndPunchTimeBetweenOrderByPunchTimeAsc(
                        deviceId, start, end, pageable);
            } else if (normalizedEmployeeNo != null) {
                punches = attendancePunchRepository.findByEmployeeNoAndPunchTimeBetweenOrderByPunchTimeAsc(
                        normalizedEmployeeNo, start, end, pageable);
            } else {
                punches = attendancePunchRepository.findByPunchTimeBetweenOrderByPunchTimeAsc(start, end, pageable);
            }
        } else {
            if (deviceId != null && normalizedEmployeeNo != null) {
                punches = attendancePunchRepository.findByDeviceIdAndEmployeeNoOrderByPunchTimeDesc(deviceId, normalizedEmployeeNo, pageable);
            } else if (deviceId != null) {
                punches = attendancePunchRepository.findByDeviceIdOrderByPunchTimeDesc(deviceId, pageable);
            } else if (normalizedEmployeeNo != null) {
                punches = attendancePunchRepository.findByEmployeeNoOrderByPunchTimeDesc(normalizedEmployeeNo, pageable);
            } else {
                punches = attendancePunchRepository.findByOrderByPunchTimeDesc(pageable);
            }
        }

        return punches.stream()
                .map(p -> new PunchResponse(
                        p.getId(),
                        p.getDeviceId(),
                        p.getEmployeeNo(),
                        p.getPunchTime(),
                        p.getRawEventId()
                ))
                .toList();
    }

    @GetMapping("/raw-events")
    public List<RawEventResponse> getRawEvents(
            @RequestHeader(value = "X-Allowed-Device-Ids", required = false) String scope,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Integer major,
            @RequestParam(required = false) Integer minor,
            @RequestParam(defaultValue = "false") boolean includeRawJson,
            @RequestParam(required = false) Integer limit
    ) {
        Pageable pageable = PageRequest.of(0, validateLimit(limit));

        List<AcsRawEventEntity> events;
        if (scope == null) {
            events = acsRawEventRepository.findRecent(deviceId, major, minor, pageable);
        } else {
            var ids = allowedDeviceIds(scope);
            events = acsRawEventRepository.findAll((root, query, cb) -> {
                var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
                predicates.add(root.get("deviceId").in(ids));
                if (deviceId != null) predicates.add(cb.equal(root.get("deviceId"), deviceId));
                if (major != null) predicates.add(cb.equal(root.get("majorEventType"), major));
                if (minor != null) predicates.add(cb.equal(root.get("subEventType"), minor));
                return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
            }, PageRequest.of(0, pageable.getPageSize(), org.springframework.data.domain.Sort.by("eventTime").descending())).getContent();
        }
        return events.stream()
                .map(e -> new RawEventResponse(
                        e.getId(),
                        e.getDeviceId(),
                        e.getSerialNo(),
                        e.getEventTime(),
                        e.getMajorEventType(),
                        e.getSubEventType(),
                        e.getEmployeeNoString(),
                        e.getCardNo(),
                        includeRawJson ? e.getRawJson() : null
                ))
                .toList();
    }

    @GetMapping("/failed-attempts")
    public List<FailedAttemptResponse> getFailedAttempts(
            @RequestHeader(value = "X-Allowed-Device-Ids", required = false) String scope,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Integer limit
    ) {
        Pageable pageable = PageRequest.of(0, validateLimit(limit));

        List<AcsFailedAttemptEntity> attempts;
        if (scope != null) {
            var ids = allowedDeviceIds(scope);
            attempts = acsFailedAttemptRepository.findAll((root, query, cb) -> deviceId == null
                    ? root.get("deviceId").in(ids)
                    : cb.and(root.get("deviceId").in(ids), cb.equal(root.get("deviceId"), deviceId)),
                    PageRequest.of(0, pageable.getPageSize(), org.springframework.data.domain.Sort.by("eventTime").descending())).getContent();
        } else attempts = deviceId == null
                ? acsFailedAttemptRepository.findByOrderByEventTimeDesc(pageable)
                : acsFailedAttemptRepository.findByDeviceIdOrderByEventTimeDesc(deviceId, pageable);

        return attempts.stream()
                .map(a -> new FailedAttemptResponse(
                        a.getId(),
                        a.getDeviceId(),
                        a.getIdentity(),
                        a.getSubEventType(),
                        a.getEventTime(),
                        a.getRawEventId()
                ))
                .toList();
    }

    @PostMapping("/acs-events/search")
    public AcsEventSearchResponse searchAcsEvents(
            @RequestParam Long deviceId,
            @RequestBody AcsEventSearchBody requestBody
    ) {
        return doSearchAcsEvents(deviceId, requestBody, false);
    }

    @PostMapping("/acs-events/image-search")
    public AcsEventSearchResponse searchAcsEventImages(
            @RequestParam Long deviceId,
            @RequestBody AcsEventSearchBody requestBody
    ) {
        return doSearchAcsEvents(deviceId, requestBody, true);
    }

    private AcsEventSearchResponse doSearchAcsEvents(
            Long deviceId,
            AcsEventSearchBody requestBody,
            boolean forcePicEnable
    ) {
        DeviceEntity device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));

        AcsEventSearchRequest.AcsEventCondRequest cond = requestBody != null ? requestBody.acsEventCond() : null;
        if (cond == null || cond.startTime() == null || cond.endTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AcsEventCond.startTime and endTime are required");
        }

        try {
            AcsEventSearchRequest.AcsEventCondRequest requestCondition = forcePicEnable
                    ? new AcsEventSearchRequest.AcsEventCondRequest(
                            cond.searchID(),
                            cond.searchResultPosition(),
                            cond.maxResults(),
                            cond.major(),
                            cond.minor(),
                            cond.startTime(),
                            cond.endTime(),
                            true,
                            cond.employeeNoString(),
                            cond.cardNo()
                    )
                    : cond;
            String requestJson = AcsEventSearchRequest.fromCondition(requestCondition);
            IsapiClient.AcsEventSearchResult result = isapiClient.searchAcsEventsOnDemand(device, requestJson);
            if (result.statusCode() == 401) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Authentication failed (401): wrong username or password for device.");
            }
            if (result.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Device AcsEvent search failed with status " + result.statusCode());
            }
            return AcsEventSearchResponse.fromJson(result.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to search ACS events", e);
        }
    }

    @GetMapping("/acs-events/picture")
    public ResponseEntity<byte[]> getAcsEventPicture(
            @RequestParam Long deviceId,
            @RequestParam String url
    ) {
        return fetchAcsEventPicture(deviceId, url);
    }

    @PostMapping("/acs-events/picture")
    public ResponseEntity<byte[]> postAcsEventPicture(
            @RequestParam Long deviceId,
            @RequestBody PictureUrlRequest body
    ) {
        if (body == null || body.url() == null || body.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        return fetchAcsEventPicture(deviceId, body.url());
    }

    private ResponseEntity<byte[]> fetchAcsEventPicture(Long deviceId, String url) {
        DeviceEntity device = resolveDeviceForPicture(deviceId, url);
        try {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                com.abv.hrerpisapi.device.DigestHttpClient.requireSameOrigin(url, "http://" + device.getIp());
            }
        } catch (IOException forbiddenOrigin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Picture origin does not match device");
        }
        try {
            byte[] imageBytes = isapiClient.downloadFaceImage(device, url)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Picture not found on device"));
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(imageBytes);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch picture from device", e);
        }
    }

    /** Use the device IP embedded in pictureURL when it differs from the requested deviceId. */
    private DeviceEntity resolveDeviceForPicture(Long deviceId, String url) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    private static String extractHostFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        int schemeSep = url.indexOf("://");
        int hostStart = schemeSep + 3;
        int pathStart = url.indexOf('/', hostStart);
        if (pathStart < 0) {
            return url.substring(hostStart);
        }
        return url.substring(hostStart, pathStart);
    }

    private static java.util.List<Long> allowedDeviceIds(String scope) {
        try {
            if (scope.isBlank()) return java.util.List.of(-1L);
            return java.util.Arrays.stream(scope.split(",")).map(Long::valueOf).toList();
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device scope");
        }
    }

    private static int validateLimit(Integer limit) {
        int resolved = limit == null ? DEFAULT_LIMIT : limit;
        if (resolved < 1 || resolved > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 500");
        }
        return resolved;
    }

    private static int validatePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        return page;
    }

    private static String normalizeToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record PunchResponse(
            Long id,
            Long deviceId,
            String employeeNo,
            OffsetDateTime punchTime,
            Long rawEventId
    ) {
    }

    public record RawEventResponse(
            Long id,
            Long deviceId,
            Long serialNo,
            OffsetDateTime eventTime,
            Integer majorEventType,
            Integer subEventType,
            String employeeNoString,
            String cardNo,
            String rawJson
    ) {
    }

    public record FailedAttemptResponse(
            Long id,
            Long deviceId,
            String identity,
            Integer subEventType,
            OffsetDateTime eventTime,
            Long rawEventId
    ) {
    }

    public record AcsEventSearchBody(
            @JsonProperty("AcsEventCond") AcsEventSearchRequest.AcsEventCondRequest acsEventCond
    ) {
    }

    public record PictureUrlRequest(String url) {
    }
}
