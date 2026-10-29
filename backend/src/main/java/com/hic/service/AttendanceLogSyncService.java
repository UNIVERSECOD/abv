package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO;
import com.hic.exception.DeviceSyncException;
import com.hic.exception.UpstreamApiException;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.TenantContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
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

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceLogSyncService {

    private final RestTemplate restTemplate;
    private final EmployeeRepository employeeRepository;
    private final DeviceConfigRepository deviceConfigRepository;

    @Value("${isapi.base-url}")
    private String isapiBaseUrl;

    public List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> getAttendanceLogs(
            Long deviceId,
            String employeeNo,
            Integer limit
    ) {
        java.net.URI url = UriComponentsBuilder
                .fromHttpUrl(basePunchesUrl())
                .queryParamIfPresent("deviceId", Optional.ofNullable(deviceId))
                .queryParamIfPresent("employeeNo", Optional.ofNullable(normalizeToNull(employeeNo)))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .build().toUri();

        List<IsapiPunchResponse> punches = exchangeForList(url);
        return filterToTenantDevices(mapPunches(punches));
    }

    public List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> getAttendanceLogs(
            Long deviceId,
            String employeeNo,
            OffsetDateTime start,
            OffsetDateTime end,
            Integer page,
            Integer size
    ) {
        // Single-page fetch when caller paginates explicitly (door sync loops pages itself).
        if (page != null || size != null) {
            return filterToTenantDevices(mapPunches(exchangeForList(buildPunchesUri(
                    deviceId, employeeNo, start, end, page != null ? page : 0, size != null ? size : 500
            ))));
        }

        // Access-logs UI sends a date range without page/size — pull all pages so recent
        // punches are not truncated by ISAPI's default 50 ASC window.
        int pageSize = 500;
        int currentPage = 0;
        List<IsapiPunchResponse> all = new java.util.ArrayList<>();
        while (currentPage < 100) {
            List<IsapiPunchResponse> batch = exchangeForList(buildPunchesUri(
                    deviceId, employeeNo, start, end, currentPage, pageSize
            ));
            if (batch.isEmpty()) {
                break;
            }
            all.addAll(batch);
            if (batch.size() < pageSize) {
                break;
            }
            currentPage++;
        }
        return filterToTenantDevices(mapPunches(all));
    }

    private java.net.URI buildPunchesUri(
            Long deviceId,
            String employeeNo,
            OffsetDateTime start,
            OffsetDateTime end,
            Integer page,
            Integer size
    ) {
        return UriComponentsBuilder
                .fromHttpUrl(basePunchesUrl())
                .queryParamIfPresent("deviceId", Optional.ofNullable(deviceId))
                .queryParamIfPresent("employeeNo", Optional.ofNullable(normalizeToNull(employeeNo)))
                .queryParam("start", start != null ? start.toInstant().toString() : null)
                .queryParam("end", end != null ? end.toInstant().toString() : null)
                .queryParamIfPresent("page", Optional.ofNullable(page))
                .queryParamIfPresent("size", Optional.ofNullable(size))
                .build().toUri();
    }

    private List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> mapPunches(List<IsapiPunchResponse> punches) {
        if (punches == null || punches.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> employeeNos = punches.stream()
                .map(IsapiPunchResponse::getEmployeeNo)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();

        Map<String, Employee> employeeMap = new HashMap<>();
        Map<String, List<Employee>> ambiguousTerminalNumbers = new HashMap<>();
        if (!employeeNos.isEmpty()) {
            Long tenantId = TenantContext.getTenantId();
            List<Employee> employees = tenantId != null
                    ? employeeRepository.findByTenantIdAndEmployeeIdIn(tenantId, employeeNos)
                    : employeeRepository.findByEmployeeIdIn(employeeNos);
            for (Employee emp : employees) {
                indexEmployee(employeeMap, emp);
            }
            for (String no : employeeNos) {
                List<Employee> byDeviceNo = tenantId != null
                        ? employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(tenantId, no)
                        : employeeRepository.findByDeviceEmployeeNoIgnoreCase(no);
                if (byDeviceNo.size() == 1) {
                    // The raw ISAPI identity is a terminal number, not another person's HR code.
                    employeeMap.put(no.toLowerCase(java.util.Locale.ROOT), byDeviceNo.get(0));
                } else if (byDeviceNo.size() > 1) {
                    employeeMap.remove(no.toLowerCase(java.util.Locale.ROOT));
                    ambiguousTerminalNumbers.put(no.toLowerCase(java.util.Locale.ROOT), byDeviceNo);
                }
            }
        }

        Map<Long, DeviceConfig> deviceMap = loadDevices(punches);
        Map<String, Employee> resolvedAmbiguous = new HashMap<>();

        return punches.stream()
                .map(response -> {
                    String key = response.getEmployeeNo() != null ? response.getEmployeeNo().trim().toLowerCase(java.util.Locale.ROOT) : null;
                    Employee emp = key != null ? employeeMap.get(key) : null;
                    DeviceConfig device = response.getDeviceId() != null ? deviceMap.get(response.getDeviceId()) : null;
                    if (key != null && ambiguousTerminalNumbers.containsKey(key) && device != null) {
                        String pair = device.getId() + ":" + key;
                        if (!resolvedAmbiguous.containsKey(pair)) {
                            var matches = employeeRepository.findByDeviceAccessAndDeviceEmployeeNo(device.getId(), response.getEmployeeNo().trim())
                                    .stream().filter(e -> device.getTenantId() == null || device.getTenantId().equals(e.getTenantId())).toList();
                            if (matches.size() != 1 && device.getBranchId() != null) {
                                matches = ambiguousTerminalNumbers.get(key).stream()
                                        .filter(e -> device.getBranchId().equals(e.getBranchId())).toList();
                            }
                            resolvedAmbiguous.put(pair, matches.size() == 1 ? matches.get(0) : null);
                        }
                        emp = resolvedAmbiguous.get(pair);
                    }
                    String firstName = emp != null ? emp.getFirstName() : null;
                    String lastName = emp != null ? emp.getLastName() : null;
                    String deviceName = device != null ? device.getDeviceName() : null;
                    String doorRole = device != null ? device.getDoorRole() : null;
                    return new AttendanceLogSyncDTO.AttendanceLogEntryDTO(
                            response.getId(),
                            response.getDeviceId(),
                            response.getEmployeeNo(),
                            response.getPunchTime(),
                            response.getRawEventId(),
                            firstName,
                            lastName,
                            deviceName,
                            doorRole
                    );
                })
                .toList();
    }

    private Map<Long, DeviceConfig> loadDevices(List<IsapiPunchResponse> punches) {
        Set<Long> punchDeviceIds = punches.stream()
                .map(IsapiPunchResponse::getDeviceId)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(HashSet::new));
        if (punchDeviceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Long tenantId = TenantContext.getTenantId();
        List<DeviceConfig> devices = tenantId != null
                ? deviceConfigRepository.findByTenantId(tenantId)
                : deviceConfigRepository.findAll();

        Map<Long, DeviceConfig> byIsapiId = new HashMap<>();
        for (DeviceConfig device : devices) {
            if (device.getDeviceId() != null && !device.getDeviceId().isBlank()) {
                try {
                    byIsapiId.put(Long.valueOf(device.getDeviceId().trim()), device);
                } catch (NumberFormatException ignored) {
                    // non-numeric deviceId strings are ignored for punch mapping
                }
            }
        }

        Map<Long, DeviceConfig> matched = new HashMap<>();
        for (Long punchDeviceId : punchDeviceIds) {
            DeviceConfig device = byIsapiId.get(punchDeviceId);
            if (device != null) {
                matched.put(punchDeviceId, device);
            }
        }
        return matched;
    }

    /**
     * When listing without an explicit device filter, keep only punches that belong to this tenant's devices.
     */
    private List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> filterToTenantDevices(
            List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> logs
    ) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || logs.isEmpty()) {
            return logs;
        }
        Set<Long> tenantIsapiIds = new HashSet<>();
        for (DeviceConfig device : deviceConfigRepository.findByTenantId(tenantId)) {
            if (device.getDeviceId() != null && !device.getDeviceId().isBlank()) {
                try {
                    tenantIsapiIds.add(Long.valueOf(device.getDeviceId().trim()));
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        if (tenantIsapiIds.isEmpty()) {
            return Collections.emptyList();
        }
        return logs.stream()
                .filter(log -> log.getDeviceId() != null && tenantIsapiIds.contains(log.getDeviceId()))
                .toList();
    }

    private static void indexEmployee(Map<String, Employee> employeeMap, Employee emp) {
        if (emp.getEmployeeId() != null) {
            employeeMap.put(emp.getEmployeeId().trim().toLowerCase(java.util.Locale.ROOT), emp);
        }
        if (emp.getDeviceEmployeeNo() != null && !emp.getDeviceEmployeeNo().isBlank()) {
            employeeMap.putIfAbsent(emp.getDeviceEmployeeNo().trim().toLowerCase(java.util.Locale.ROOT), emp);
        }
    }

    private String basePunchesUrl() {
        return trimTrailingSlash(isapiBaseUrl) + "/api/punches";
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String normalizeToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private List<IsapiPunchResponse> exchangeForList(java.net.URI url) {
        try {
            ResponseEntity<List<IsapiPunchResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    new ParameterizedTypeReference<>() {
                    }
            );
            return response.getBody() == null ? Collections.emptyList() : response.getBody();
        } catch (HttpStatusCodeException ex) {
            throw new UpstreamApiException(ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            throw new DeviceSyncException("ISAPI service is unavailable");
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class IsapiPunchResponse {
        private Long id;
        private Long deviceId;
        private String employeeNo;
        private OffsetDateTime punchTime;
        private Long rawEventId;
    }
}
