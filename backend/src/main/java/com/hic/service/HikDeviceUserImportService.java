package com.hic.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.dto.DeviceEmployeeImportDTO;
import com.hic.dto.HikUserInfoSearchDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.Branch;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.model.EmployeeArea;
import com.hic.model.EmployeeDeviceAccess;
import com.hic.repository.BranchRepository;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeDeviceAccessRepository;
import com.hic.repository.EmployeeAreaRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.TenantRepository;
import com.hic.util.EncryptionUtil;
import com.hic.util.TenantContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Setup-team import: pull persons and face images from Hikvision devices into local employees.
 *
 * <p>Match key is device {@code employeeNo} (person ID). HikCentral Professional
 * documents name-based matching for "Import from Device"; we use employeeNo because
 * it is the stable ISAPI identity and what attendance already resolves against.
 *
 * <p>Face photos come from FDLib FDSearch: MatchList.FPID is matched to UserInfo.employeeNo
 * (stored as {@code device_employee_no}). Images are downloaded via isapi Digest auth and
 * stored locally under {@code face_data} / {@code uploads/faces}, served by
 * {@code GET /api/faces/employee/{id}/image}.
 *
 * <p>Across devices of a branch: same employeeNo + consistent name → one employee;
 * same employeeNo + conflicting names → skip and flag; already in DB → skip employee
 * fields but link missing device access. Faces are only taken from devices the employee
 * is linked to.
 *
 * <p>Optional post-step ({@code writeToOtherBranchDevices}, default on): after import,
 * persons found on any scanned device are also written to sibling devices of the same
 * branch (user record + face when a profile image exists).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HikDeviceUserImportService {

    private static final String SEARCH_PATH = "/ISAPI/AccessControl/UserInfo/Search?format=json";
    private static final int PAGE_SIZE = 30;
    private static final int FACE_USER_LOOKUP_MAX_ATTEMPTS = 3;
    private static final long FACE_USER_LOOKUP_RETRY_DELAY_MS = 300L;

    private final RestTemplate restTemplate;
    private final DeviceConfigRepository deviceConfigRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeDeviceAccessRepository employeeDeviceAccessRepository;
    private final EmployeeAreaRepository employeeAreaRepository;
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;
    private final EncryptionUtil encryptionUtil;
    private final ObjectMapper objectMapper;
    private final EmployeeFaceImageService employeeFaceImageService;
    private final IsapiEmployeeUserSyncService isapiEmployeeUserSyncService;

    @Value("${isapi.base-url:}")
    private String isapiBaseUrl;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public HikUserInfoSearchDTO.ImportResultDTO importUsersFromDevice(Long deviceConfigId) {
        DeviceConfig device = deviceConfigRepository.findById(deviceConfigId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", deviceConfigId));
        if (device.getBranchId() == null) {
            throw new BadRequestException("Device is not assigned to a branch");
        }
        DeviceEmployeeImportDTO.ImportRequest request = new DeviceEmployeeImportDTO.ImportRequest(
                device.getBranchId(), List.of(deviceConfigId), null);
        DeviceEmployeeImportDTO.ImportResult result = importUsersFromBranch(request);
        return new HikUserInfoSearchDTO.ImportResultDTO(
                result.getTotalFetched(),
                result.getCreated(),
                result.getSkippedExisting() + result.getSkippedConflict(),
                result.getErrors(),
                result.getMessage());
    }

    @Transactional
    public DeviceEmployeeImportDTO.ImportResult importUsersFromBranch(DeviceEmployeeImportDTO.ImportRequest request) {
        if (request == null || request.getBranchId() == null) {
            throw new BadRequestException("branchId is required");
        }
        Long branchId = request.getBranchId();
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        List<DeviceConfig> branchDevices = deviceConfigRepository.findByBranchId(branchId);
        if (branchDevices.isEmpty()) {
            throw new BadRequestException("No devices found for branch " + branchId);
        }

        List<DeviceConfig> devices = selectDevices(branchDevices, request.getDeviceConfigIds(), branchId);
        Long tenantId = resolveTenantId(devices);
        String branchPrefix = resolveBranchPrefix(branch);

        DeviceEmployeeImportDTO.ImportResult result = new DeviceEmployeeImportDTO.ImportResult();
        result.setBranchId(branchId);
        result.setBranchName(branch.getName());
        result.setBranchPrefix(branchPrefix);

        Map<String, AggregatedPerson> byEmployeeNo = new LinkedHashMap<>();
        int totalFetched = 0;

        for (DeviceConfig device : devices) {
            DeviceEmployeeImportDTO.DeviceScanStatus status = new DeviceEmployeeImportDTO.DeviceScanStatus();
            status.setDeviceConfigId(device.getId());
            status.setDeviceName(device.getDeviceName());
            status.setDeviceIp(device.getDeviceIp());
            try {
                List<FetchedPerson> users = fetchUsersFromDevice(device);
                status.setSuccess(true);
                status.setUserCount(users.size());
                totalFetched += users.size();
                for (FetchedPerson person : users) {
                    mergePerson(byEmployeeNo, person, device.getId());
                }
            } catch (Exception ex) {
                log.error("Failed to fetch users from deviceConfigId={}: {}", device.getId(), ex.getMessage(), ex);
                status.setSuccess(false);
                status.setUserCount(0);
                status.setError(ex.getMessage());
                result.setDevicesFailed(result.getDevicesFailed() + 1);
            }
            result.getDeviceStatuses().add(status);
        }

        result.setDevicesScanned(devices.size());
        result.setTotalFetched(totalFetched);
        result.setUniquePersons(byEmployeeNo.size());

        for (AggregatedPerson person : byEmployeeNo.values()) {
            try {
                persistAggregated(person, tenantId, branchId, branchPrefix, result);
            } catch (Exception ex) {
                log.error("Failed to persist imported person {}: {}", person.employeeNo, ex.getMessage(), ex);
                result.setErrors(result.getErrors() + 1);
                result.getErrorsDetail().add(new DeviceEmployeeImportDTO.SkippedPerson(
                        person.employeeNo, person.name, ex.getMessage(),
                        new ArrayList<>(person.deviceConfigIds)));
            }
        }

        // After employees exist (or are linked), pull FDLib faces from each successful device
        // and associate them by FPID ↔ device employeeNo. Never assign a face without a match.
        for (DeviceEmployeeImportDTO.DeviceScanStatus status : result.getDeviceStatuses()) {
            if (!status.isSuccess() || status.getDeviceConfigId() == null) {
                continue;
            }
            DeviceConfig device = devices.stream()
                    .filter(d -> status.getDeviceConfigId().equals(d.getId()))
                    .findFirst()
                    .orElse(null);
            if (device == null) {
                continue;
            }
            try {
                syncFacesFromDevice(device, byEmployeeNo, result, status);
            } catch (Exception ex) {
                log.error("Face sync failed for deviceConfigId={}: {}", device.getId(), ex.getMessage(), ex);
                result.setFacesFailed(result.getFacesFailed() + 1);
                if (status.getError() == null) {
                    status.setError("Face sync: " + ex.getMessage());
                }
            }
        }

        int stillWithoutFace = 0;
        for (AggregatedPerson person : byEmployeeNo.values()) {
            if (person.conflict || person.employeePk == null) {
                continue;
            }
            if (!employeeFaceImageService.hasFaceImage(person.employeePk)) {
                stillWithoutFace++;
            }
        }
        result.setFacesSkippedNoMatch(stillWithoutFace);

        boolean writeToOthers = request.getWriteToOtherBranchDevices() == null
                || Boolean.TRUE.equals(request.getWriteToOtherBranchDevices());
        if (writeToOthers) {
            writePersonsToOtherBranchDevices(branchDevices, byEmployeeNo, result);
        }

        result.setMessage(String.format(
                "Import complete: branch=%s prefix=%s devices=%d failedDevices=%d fetched=%d unique=%d created=%d skippedExisting=%d crossBranchLinked=%d skippedConflict=%d accessLinked=%d facesSynced=%d facesSkippedExisting=%d facesSkippedNoMatch=%d facesFailed=%d writtenToOther=%d alreadyOnOther=%d otherWriteFailed=%d facesOther=%d facesOtherFailed=%d errors=%d",
                branch.getName(), branchPrefix, result.getDevicesScanned(), result.getDevicesFailed(),
                result.getTotalFetched(), result.getUniquePersons(), result.getCreated(),
                result.getSkippedExisting(), result.getCrossBranchLinked(), result.getSkippedConflict(),
                result.getAccessLinked(), result.getFacesSynced(), result.getFacesSkippedExisting(),
                result.getFacesSkippedNoMatch(), result.getFacesFailed(),
                result.getWrittenToOtherDevices(), result.getAlreadyOnOtherDevices(),
                result.getOtherDeviceWriteFailed(), result.getFacesWrittenToOtherDevices(),
                result.getFacesOtherDeviceFailed(), result.getErrors()));
        log.info(result.getMessage());
        return result;
    }

    // -------------------------------------------------------------------------
    // Device selection / fetch
    // -------------------------------------------------------------------------

    private List<DeviceConfig> selectDevices(
            List<DeviceConfig> branchDevices, List<Long> requestedIds, Long branchId) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return branchDevices;
        }
        Set<Long> allowed = new LinkedHashSet<>();
        for (DeviceConfig d : branchDevices) {
            allowed.add(d.getId());
        }
        List<DeviceConfig> selected = new ArrayList<>();
        for (Long id : requestedIds) {
            if (id == null) continue;
            if (!allowed.contains(id)) {
                throw new BadRequestException("Device " + id + " does not belong to branch " + branchId);
            }
            branchDevices.stream()
                    .filter(d -> d.getId().equals(id))
                    .findFirst()
                    .ifPresent(selected::add);
        }
        if (selected.isEmpty()) {
            throw new BadRequestException("No valid devices selected for import");
        }
        return selected;
    }

    private List<FetchedPerson> fetchUsersFromDevice(DeviceConfig device) {
        Long isapiId = resolveIsapiDeviceId(device);
        if (isapiId != null && StringUtils.hasText(isapiBaseUrl)) {
            try {
                return fetchViaIsapiService(isapiId);
            } catch (Exception ex) {
                log.warn("ISAPI Digest pull failed for deviceConfigId={}, falling back to direct Basic: {}",
                        device.getId(), ex.getMessage());
            }
        }
        return fetchViaDirectBasic(device);
    }

    private List<FetchedPerson> fetchViaIsapiService(Long isapiDeviceId) throws Exception {
        String url = trimTrailingSlash(isapiBaseUrl) + "/api/devices/" + isapiDeviceId + "/users/from-device";
        ResponseEntity<String> raw = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        if (raw.getBody() == null) {
            return List.of();
        }
        List<IsapiPersonDto> list = objectMapper.readValue(raw.getBody(), new TypeReference<>() {});
        List<FetchedPerson> result = new ArrayList<>();
        for (IsapiPersonDto dto : list) {
            if (dto == null || !StringUtils.hasText(dto.getEmployeeNo())) continue;
            result.add(new FetchedPerson(
                    dto.getEmployeeNo().trim(),
                    dto.getName(),
                    dto.getGender(),
                    dto.getBeginTime()));
        }
        return result;
    }

    private List<FetchedPerson> fetchViaDirectBasic(DeviceConfig device) {
        if (!StringUtils.hasText(device.getPasswordEncrypted())) {
            throw new IllegalStateException(
                    "Device credential is unavailable; edit the device and enter its password before retrying");
        }
        String baseUrl = buildBaseUrl(device);
        String username = device.getUsername();
        String password = decryptPassword(device.getPasswordEncrypted());
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "Device credential is unavailable; edit the device and enter its password before retrying");
        }
        String url = baseUrl + SEARCH_PATH;
        HttpHeaders headers = buildHeaders(username, password);

        List<FetchedPerson> result = new ArrayList<>();
        String searchId = "sync_users_" + device.getId();
        int offset = 0;

        while (true) {
            HikUserInfoSearchDTO.SearchRequest body = new HikUserInfoSearchDTO.SearchRequest(
                    new HikUserInfoSearchDTO.SearchCond(searchId, PAGE_SIZE, offset));
            HttpEntity<HikUserInfoSearchDTO.SearchRequest> entity = new HttpEntity<>(body, headers);

            try {
                ResponseEntity<String> raw = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                if (raw.getBody() == null) break;

                HikUserInfoSearchDTO.SearchResponse response =
                        objectMapper.readValue(raw.getBody(), HikUserInfoSearchDTO.SearchResponse.class);
                HikUserInfoSearchDTO.SearchResult sr = response.getUserInfoSearch();
                if (sr == null) break;

                String status = sr.getResponseStatusStrg();
                List<HikUserInfoSearchDTO.UserInfo> page = sr.getUserInfoList();
                if (page == null || page.isEmpty()
                        || (status != null && status.toUpperCase(Locale.ROOT).contains("NO"))) {
                    break;
                }

                int got = 0;
                for (HikUserInfoSearchDTO.UserInfo u : page) {
                    if (u == null || !StringUtils.hasText(u.getEmployeeNo())) continue;
                    String begin = u.getValid() != null ? u.getValid().getBeginTime() : null;
                    result.add(new FetchedPerson(u.getEmployeeNo().trim(), u.getName(), u.getGender(), begin));
                    got++;
                }

                int numOfMatches = sr.getNumOfMatches() > 0 ? sr.getNumOfMatches() : got;
                if ("OK".equalsIgnoreCase(status) || numOfMatches < PAGE_SIZE || got == 0) {
                    break;
                }
                offset += numOfMatches;
                if (offset > 100_000) break;
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "Direct device UserInfo/Search failed for " + device.getDeviceIp() + ": " + ex.getMessage(),
                        ex);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Merge + persist
    // -------------------------------------------------------------------------

    private void mergePerson(Map<String, AggregatedPerson> byEmployeeNo, FetchedPerson person, Long deviceConfigId) {
        String key = person.employeeNo().toLowerCase(Locale.ROOT);
        AggregatedPerson agg = byEmployeeNo.get(key);
        if (agg == null) {
            agg = new AggregatedPerson();
            agg.employeeNo = person.employeeNo();
            agg.name = person.name();
            agg.gender = person.gender();
            agg.beginTime = person.beginTime();
            agg.deviceConfigIds.add(deviceConfigId);
            byEmployeeNo.put(key, agg);
            return;
        }
        agg.deviceConfigIds.add(deviceConfigId);
        if (agg.conflict) {
            return;
        }
        String existingNorm = normalizeName(agg.name);
        String incomingNorm = normalizeName(person.name());
        if (!existingNorm.isEmpty() && !incomingNorm.isEmpty() && !existingNorm.equals(incomingNorm)) {
            agg.conflict = true;
            agg.conflictReason = "Name mismatch across devices: \"" + agg.name + "\" vs \"" + person.name() + "\"";
            return;
        }
        if (!StringUtils.hasText(agg.name) && StringUtils.hasText(person.name())) {
            agg.name = person.name();
        }
        if (!StringUtils.hasText(agg.gender) && StringUtils.hasText(person.gender())) {
            agg.gender = person.gender();
        }
        if (!StringUtils.hasText(agg.beginTime) && StringUtils.hasText(person.beginTime())) {
            agg.beginTime = person.beginTime();
        }
    }

    private void persistAggregated(
            AggregatedPerson person,
            Long tenantId,
            Long branchId,
            String branchPrefix,
            DeviceEmployeeImportDTO.ImportResult result) {

        if (person.conflict) {
            result.setSkippedConflict(result.getSkippedConflict() + 1);
            result.getConflicts().add(new DeviceEmployeeImportDTO.SkippedPerson(
                    person.employeeNo, person.name, person.conflictReason,
                    new ArrayList<>(person.deviceConfigIds)));
            return;
        }

        String prefixedId = buildPrefixedEmployeeId(branchPrefix, person.employeeNo);

        // Same person already imported for this branch (prefixed id or home-branch + device no).
        Optional<Employee> sameBranchExisting = findExistingForBranch(tenantId, branchId, prefixedId, person.employeeNo);
        if (sameBranchExisting.isPresent()) {
            result.setSkippedExisting(result.getSkippedExisting() + 1);
            Employee existing = sameBranchExisting.get();
            if (Employee.EmploymentStatus.TERMINATED.equals(existing.getEmploymentStatus())) {
                log.info("Skipping terminated employee during device import: employeeId={} deviceEmployeeNo={}",
                        existing.getEmployeeId(), person.employeeNo);
                return;
            }
            ensureDeviceEmployeeNo(existing, person.employeeNo);
            ensureAreaMembership(existing, branchId);
            int linked = linkMissingAccess(existing, person.deviceConfigIds, branchId);
            result.setAccessLinked(result.getAccessLinked() + linked);
            person.employeePk = existing.getId();
            return;
        }

        // Same device person + name already exists under another home branch → cross-branch access only.
        Optional<Employee> crossBranchSamePerson = findCrossBranchSamePerson(tenantId, person.employeeNo, person.name);
        if (crossBranchSamePerson.isPresent()) {
            Employee existing = crossBranchSamePerson.get();
            if (Employee.EmploymentStatus.TERMINATED.equals(existing.getEmploymentStatus())) {
                result.setSkippedExisting(result.getSkippedExisting() + 1);
                log.info("Skipping terminated cross-branch employee during device import: employeeId={} deviceEmployeeNo={}",
                        existing.getEmployeeId(), person.employeeNo);
                return;
            }
            ensureDeviceEmployeeNo(existing, person.employeeNo);
            ensureAreaMembership(existing, branchId);
            int linked = linkMissingAccess(existing, person.deviceConfigIds, branchId);
            result.setAccessLinked(result.getAccessLinked() + linked);
            result.setCrossBranchLinked(result.getCrossBranchLinked() + 1);
            result.setSkippedExisting(result.getSkippedExisting() + 1);
            person.employeePk = existing.getId();
            return;
        }

        Employee emp = mapToEmployee(person, tenantId, branchId, prefixedId);
        enforceEmployeeQuota(tenantId);
        Employee saved = employeeRepository.save(emp);
        ensureAreaMembership(saved, branchId);
        int linked = linkMissingAccess(saved, person.deviceConfigIds, branchId);
        result.setAccessLinked(result.getAccessLinked() + linked);
        result.setCreated(result.getCreated() + 1);
        person.employeePk = saved.getId();
        result.getCreatedPersons().add(new DeviceEmployeeImportDTO.CreatedPerson(
                saved.getId(),
                person.employeeNo,
                saved.getEmployeeId(),
                saved.getDeviceEmployeeNo(),
                saved.getBranchId(),
                saved.getFirstName(),
                saved.getLastName(),
                new ArrayList<>(person.deviceConfigIds)));
    }

    /**
     * Pulls FDLib face records from one device and saves matching images as employee profile photos.
     * Match key: FDSearch FPID ↔ UserInfo.employeeNo (stored as {@code device_employee_no}).
     * This equals the FPID used when this project uploads faces via FDLib FDSetUp.
     * Faces are only taken from the device the employee is linked to — never cross-device.
     */
    private void syncFacesFromDevice(
            DeviceConfig device,
            Map<String, AggregatedPerson> byEmployeeNo,
            DeviceEmployeeImportDTO.ImportResult result,
            DeviceEmployeeImportDTO.DeviceScanStatus status) {

        Long isapiId = resolveIsapiDeviceId(device);
        if (isapiId == null || !StringUtils.hasText(isapiBaseUrl)) {
            log.warn("Skipping face sync for deviceConfigId={}: isapi device id or base-url missing",
                    device.getId());
            return;
        }

        Map<String, String> faceUrlByFpid = fetchFaceIndexFromDevice(isapiId);
        status.setFaceCount(faceUrlByFpid.size());
        if (faceUrlByFpid.isEmpty()) {
            log.info("Bulk face index is empty on deviceConfigId={} isapiId={}; trying each employee number",
                    device.getId(), isapiId);
        }

        int syncedOnDevice = 0;
        int individuallyDiscovered = 0;
        for (AggregatedPerson person : byEmployeeNo.values()) {
            if (person.conflict || person.employeePk == null) {
                continue;
            }
            if (!person.deviceConfigIds.contains(device.getId())) {
                continue;
            }

            String fpidKey = person.employeeNo.toLowerCase(Locale.ROOT);
            boolean listedInBulkIndex = faceUrlByFpid.containsKey(fpidKey);

            if (employeeFaceImageService.hasFaceImage(person.employeePk)) {
                result.setFacesSkippedExisting(result.getFacesSkippedExisting() + 1);
                continue;
            }

            try {
                Optional<byte[]> imageBytes = downloadFaceBytesFromDevice(isapiId, person.employeeNo);
                if (imageBytes.isEmpty()) {
                    if (listedInBulkIndex) {
                        result.setFacesFailed(result.getFacesFailed() + 1);
                    }
                    continue;
                }
                employeeFaceImageService.saveFaceImageBytes(person.employeePk, imageBytes.get(), "jpg");
                result.setFacesSynced(result.getFacesSynced() + 1);
                syncedOnDevice++;
                if (!listedInBulkIndex) {
                    individuallyDiscovered++;
                }
            } catch (Exception ex) {
                log.warn("Failed to sync face for employeeNo={} deviceConfigId={}: {}",
                        person.employeeNo, device.getId(), ex.getMessage());
                result.setFacesFailed(result.getFacesFailed() + 1);
            }
        }
        status.setFaceCount(faceUrlByFpid.size() + individuallyDiscovered);
        status.setFacesSynced(syncedOnDevice);
        log.info("Face sync for deviceConfigId={} isapiId={}: faceIndex={} individuallyDiscovered={} synced={}",
                device.getId(), isapiId, faceUrlByFpid.size(), individuallyDiscovered, syncedOnDevice);
    }

    /**
     * Writes imported persons onto sibling devices of the same branch where they were missing.
     * Source of truth for "already present on scan" is {@code person.deviceConfigIds}.
     * Access is linked for every branch device; create+face only runs for devices not in that set.
     */
    private void writePersonsToOtherBranchDevices(
            List<DeviceConfig> branchDevices,
            Map<String, AggregatedPerson> byEmployeeNo,
            DeviceEmployeeImportDTO.ImportResult result) {

        if (branchDevices == null || branchDevices.isEmpty() || byEmployeeNo == null || byEmployeeNo.isEmpty()) {
            return;
        }
        if (!StringUtils.hasText(isapiBaseUrl)) {
            log.warn("Skipping write-to-other-branch-devices: isapi.base-url is not configured");
            return;
        }

        for (AggregatedPerson person : byEmployeeNo.values()) {
            if (person == null || person.conflict || person.employeePk == null) {
                continue;
            }
            Employee employee = employeeRepository.findById(person.employeePk).orElse(null);
            if (employee == null
                    || Employee.EmploymentStatus.TERMINATED.equals(employee.getEmploymentStatus())) {
                continue;
            }

            Set<Long> allBranchDeviceIds = new LinkedHashSet<>();
            for (DeviceConfig d : branchDevices) {
                if (d != null && d.getId() != null) {
                    allBranchDeviceIds.add(d.getId());
                }
            }
            Long sourceAreaId = branchDevices.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(DeviceConfig::getBranchId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(employee.getBranchId());
            ensureAreaMembership(employee, sourceAreaId);
            int linked = linkMissingAccess(employee, allBranchDeviceIds, sourceAreaId);
            result.setAccessLinked(result.getAccessLinked() + linked);

            Set<Long> foundOnScan = new LinkedHashSet<>(person.deviceConfigIds);
            for (DeviceConfig target : branchDevices) {
                if (target == null || target.getId() == null) {
                    continue;
                }
                if (foundOnScan.contains(target.getId())) {
                    continue;
                }

                Long isapiId = resolveIsapiDeviceId(target);
                if (isapiId == null) {
                    result.setOtherDeviceWriteFailed(result.getOtherDeviceWriteFailed() + 1);
                    result.getErrorsDetail().add(new DeviceEmployeeImportDTO.SkippedPerson(
                            person.employeeNo,
                            person.name,
                            "Digər cihazda ISAPI device id yoxdur: " + target.getDeviceName(),
                            List.of(target.getId())));
                    continue;
                }

                try {
                    isapiEmployeeUserSyncService.syncEmployee(employee, List.of(isapiId));
                    person.deviceConfigIds.add(target.getId());
                    result.setWrittenToOtherDevices(result.getWrittenToOtherDevices() + 1);
                    pushFaceToDeviceIfAvailable(employee, isapiId, result);
                } catch (RuntimeException ex) {
                    if (isAlreadyExistsOnDevice(ex)) {
                        person.deviceConfigIds.add(target.getId());
                        result.setAlreadyOnOtherDevices(result.getAlreadyOnOtherDevices() + 1);
                        pushFaceToDeviceIfAvailable(employee, isapiId, result);
                    } else {
                        log.warn("Failed writing employee {} to sibling deviceConfigId={}: {}",
                                employee.getEmployeeId(), target.getId(), ex.getMessage());
                        result.setOtherDeviceWriteFailed(result.getOtherDeviceWriteFailed() + 1);
                        result.getErrorsDetail().add(new DeviceEmployeeImportDTO.SkippedPerson(
                                person.employeeNo,
                                person.name,
                                "Digər cihaza yazıla bilmədi (" + (target.getDeviceName() != null
                                        ? target.getDeviceName() : target.getId()) + "): " + ex.getMessage(),
                                List.of(target.getId())));
                    }
                }
            }
        }
    }

    private void pushFaceToDeviceIfAvailable(
            Employee employee,
            Long isapiDeviceId,
            DeviceEmployeeImportDTO.ImportResult result) {
        Optional<EmployeeFaceImageService.FaceImageData> faceOpt =
                employeeFaceImageService.getLatestFaceImage(employee.getId());
        if (faceOpt.isEmpty()) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(faceOpt.get().path());
            Long deviceUserId = findDeviceUserIdByEmployeeNoWithRetry(isapiDeviceId, resolveDevicePersonNo(employee));
            if (deviceUserId == null) {
                result.setFacesOtherDeviceFailed(result.getFacesOtherDeviceFailed() + 1);
                return;
            }
            uploadFaceBytesToDevice(isapiDeviceId, deviceUserId, bytes, faceOpt.get().contentType());
            result.setFacesWrittenToOtherDevices(result.getFacesWrittenToOtherDevices() + 1);
        } catch (HttpStatusCodeException ex) {
            if (isFaceAlreadyExistsOnDevice(ex)) {
                log.info("Face already exists on device {} for employee {}",
                        isapiDeviceId, employee.getEmployeeId());
                return;
            }
            log.warn("Face push to sibling device {} failed for employee {}: {}",
                    isapiDeviceId, employee.getEmployeeId(), ex.getMessage());
            result.setFacesOtherDeviceFailed(result.getFacesOtherDeviceFailed() + 1);
        } catch (Exception ex) {
            log.warn("Face push to sibling device {} failed for employee {}: {}",
                    isapiDeviceId, employee.getEmployeeId(), ex.getMessage());
            result.setFacesOtherDeviceFailed(result.getFacesOtherDeviceFailed() + 1);
        }
    }

    private Long findDeviceUserIdByEmployeeNoWithRetry(Long isapiDeviceId, String employeeNo) throws Exception {
        for (int attempt = 1; attempt <= FACE_USER_LOOKUP_MAX_ATTEMPTS; attempt++) {
            Long userId = findDeviceUserIdByEmployeeNo(isapiDeviceId, employeeNo);
            if (userId != null) {
                return userId;
            }
            if (attempt < FACE_USER_LOOKUP_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(FACE_USER_LOOKUP_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private Long findDeviceUserIdByEmployeeNo(Long isapiDeviceId, String employeeNo) throws Exception {
        if (!StringUtils.hasText(employeeNo)) {
            return null;
        }
        String url = trimTrailingSlash(isapiBaseUrl) + "/api/devices/" + isapiDeviceId + "/users";
        ResponseEntity<String> raw = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        if (raw.getBody() == null) {
            return null;
        }
        // Backend proxy may wrap payload; accept raw array or { data: [...] }.
        String body = raw.getBody().trim();
        List<IsapiDeviceUserDto> users;
        if (body.startsWith("[")) {
            users = objectMapper.readValue(body, new TypeReference<>() {});
        } else {
            var root = objectMapper.readTree(body);
            var dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                users = objectMapper.convertValue(dataNode, new TypeReference<>() {});
            } else {
                users = objectMapper.convertValue(root, new TypeReference<>() {});
            }
        }
        String key = employeeNo.trim().toLowerCase(Locale.ROOT);
        for (IsapiDeviceUserDto u : users) {
            if (u != null && StringUtils.hasText(u.getEmployeeNo())
                    && key.equals(u.getEmployeeNo().trim().toLowerCase(Locale.ROOT))) {
                return u.getId();
            }
        }
        return null;
    }

    private void uploadFaceBytesToDevice(
            Long isapiDeviceId, Long deviceUserId, byte[] bytes, String contentType) {
        String url = trimTrailingSlash(isapiBaseUrl)
                + "/api/devices/" + isapiDeviceId + "/users/" + deviceUserId + "/face";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource fileResource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                if (contentType != null && contentType.contains("png")) {
                    return "face.png";
                }
                if (contentType != null && contentType.contains("webp")) {
                    return "face.webp";
                }
                return "face.jpg";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Face upload HTTP " + response.getStatusCode().value());
        }
    }

    private static boolean isAlreadyExistsOnDevice(Throwable ex) {
        String msg = ex.getMessage();
        if (!StringUtils.hasText(msg)) {
            Throwable cause = ex.getCause();
            msg = cause != null ? cause.getMessage() : null;
        }
        if (!StringUtils.hasText(msg)) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("already exist")
                || lower.contains("already exists")
                || lower.contains("duplicate")
                || lower.contains("employee no already")
                || lower.contains("employeenoexist")
                || (ex instanceof HttpStatusCodeException hse && hse.getStatusCode().value() == 409);
    }

    private static boolean isFaceAlreadyExistsOnDevice(HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString();
        String message = body != null ? body : ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getStatusCode().value() == 409;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return ex.getStatusCode().value() == 409
                || lower.contains("already exist")
                || lower.contains("already exists")
                || lower.contains("duplicate");
    }

    private static String resolveDevicePersonNo(Employee employee) {
        if (StringUtils.hasText(employee.getDeviceEmployeeNo())) {
            return employee.getDeviceEmployeeNo().trim();
        }
        return employee.getEmployeeId();
    }

    private Map<String, String> fetchFaceIndexFromDevice(Long isapiDeviceId) {
        String url = trimTrailingSlash(isapiBaseUrl) + "/api/devices/" + isapiDeviceId + "/faces/from-device";
        ResponseEntity<String> raw = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        if (raw.getBody() == null) {
            return Map.of();
        }
        try {
            List<IsapiFaceDto> list = objectMapper.readValue(raw.getBody(), new TypeReference<>() {});
            Map<String, String> byFpid = new LinkedHashMap<>();
            for (IsapiFaceDto dto : list) {
                if (dto == null || !StringUtils.hasText(dto.getFpid())) {
                    continue;
                }
                String key = dto.getFpid().trim().toLowerCase(Locale.ROOT);
                // First face wins; keep association device-scoped by caller.
                byFpid.putIfAbsent(key, dto.getFaceUrl());
            }
            return byFpid;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse face index from isapi: " + ex.getMessage(), ex);
        }
    }

    private Optional<byte[]> downloadFaceBytesFromDevice(Long isapiDeviceId, String employeeNo) {
        String encoded = org.springframework.web.util.UriUtils.encodePathSegment(
                employeeNo, StandardCharsets.UTF_8);
        String url = trimTrailingSlash(isapiBaseUrl)
                + "/api/devices/" + isapiDeviceId + "/faces/" + encoded + "/download";
        ResponseEntity<String> raw = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        if (raw.getBody() == null) {
            return Optional.empty();
        }
        try {
            IsapiFaceDownloadDto dto = objectMapper.readValue(raw.getBody(), IsapiFaceDownloadDto.class);
            if (dto == null || !"SUCCESS".equalsIgnoreCase(dto.getStatus())
                    || !StringUtils.hasText(dto.getImageBase64())) {
                return Optional.empty();
            }
            return Optional.of(Base64.getDecoder().decode(dto.getImageBase64()));
        } catch (Exception ex) {
            log.warn("Failed to decode face download for employeeNo={}: {}", employeeNo, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Employee> findExistingForBranch(
            Long tenantId, Long branchId, String prefixedId, String deviceEmployeeNo) {
        Optional<Employee> byPrefixed = tenantId != null
                ? employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(tenantId, prefixedId)
                : employeeRepository.findByEmployeeIdIgnoreCase(prefixedId);
        if (byPrefixed.isPresent()) {
            return byPrefixed;
        }

        // Legacy imports stored raw device no as employeeId.
        Optional<Employee> byRawId = tenantId != null
                ? employeeRepository.findByTenantIdAndEmployeeIdIgnoreCase(tenantId, deviceEmployeeNo)
                : employeeRepository.findByEmployeeIdIgnoreCase(deviceEmployeeNo);
        if (byRawId.isPresent() && branchId.equals(byRawId.get().getBranchId())) {
            return byRawId;
        }

        List<Employee> byDeviceNo = tenantId != null
                ? employeeRepository.findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(
                        tenantId, branchId, deviceEmployeeNo)
                : List.of();
        return byDeviceNo.stream().findFirst();
    }

    private Optional<Employee> findCrossBranchSamePerson(Long tenantId, String deviceEmployeeNo, String name) {
        String nameNorm = normalizeName(name);
        if (nameNorm.isEmpty()) {
            return Optional.empty();
        }
        List<Employee> candidates = tenantId != null
                ? employeeRepository.findByTenantIdAndDeviceEmployeeNoIgnoreCase(tenantId, deviceEmployeeNo)
                : employeeRepository.findByDeviceEmployeeNoIgnoreCase(deviceEmployeeNo);
        for (Employee candidate : candidates) {
            String candidateName = ((candidate.getFirstName() != null ? candidate.getFirstName() : "")
                    + " " + (candidate.getLastName() != null ? candidate.getLastName() : "")).trim();
            if ("-".equals(candidate.getLastName())) {
                candidateName = candidate.getFirstName() != null ? candidate.getFirstName() : "";
            }
            if (nameNorm.equals(normalizeName(candidateName))) {
                return Optional.of(candidate);
            }
        }
        // Also match legacy rows where employeeId == device no and name matches.
        List<Employee> legacy = tenantId != null
                ? employeeRepository.findByTenantIdAndEmployeeIdIn(tenantId, List.of(deviceEmployeeNo))
                : employeeRepository.findByEmployeeIdIn(List.of(deviceEmployeeNo));
        for (Employee candidate : legacy) {
            if (StringUtils.hasText(candidate.getDeviceEmployeeNo())) {
                continue;
            }
            String candidateName = ((candidate.getFirstName() != null ? candidate.getFirstName() : "")
                    + " " + (candidate.getLastName() != null ? candidate.getLastName() : "")).trim();
            if (nameNorm.equals(normalizeName(candidateName))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void ensureDeviceEmployeeNo(Employee employee, String deviceEmployeeNo) {
        if (!StringUtils.hasText(employee.getDeviceEmployeeNo()) && StringUtils.hasText(deviceEmployeeNo)) {
            employee.setDeviceEmployeeNo(deviceEmployeeNo.trim());
            employeeRepository.save(employee);
        }
    }

    private int linkMissingAccess(Employee employee, Set<Long> deviceConfigIds, Long sourceAreaId) {
        int linked = 0;
        Long tenantId = employee.getTenantId();
        for (Long deviceConfigId : deviceConfigIds) {
            if (deviceConfigId == null) continue;
            if (employeeDeviceAccessRepository.existsByEmployeeIdAndDeviceConfigId(employee.getId(), deviceConfigId)) {
                continue;
            }
            EmployeeDeviceAccess access = new EmployeeDeviceAccess();
            access.setTenantId(tenantId);
            access.setEmployeeId(employee.getId());
            access.setDeviceConfigId(deviceConfigId);
            access.setAssignmentSource(EmployeeDeviceAccess.AssignmentSource.AREA);
            access.setSourceBranchId(sourceAreaId);
            employeeDeviceAccessRepository.save(access);
            linked++;
        }
        return linked;
    }

    private void ensureAreaMembership(Employee employee, Long areaId) {
        if (employee == null || employee.getId() == null || areaId == null
                || employeeAreaRepository.existsByEmployeeIdAndBranchId(employee.getId(), areaId)) {
            return;
        }
        EmployeeArea membership = new EmployeeArea();
        membership.setTenantId(employee.getTenantId());
        membership.setEmployeeId(employee.getId());
        membership.setBranchId(areaId);
        membership.setPrimary(employeeAreaRepository
                .findByEmployeeIdOrderByPrimaryDescBranchIdAsc(employee.getId()).isEmpty());
        employeeAreaRepository.save(membership);
    }

    private Employee mapToEmployee(AggregatedPerson person, Long tenantId, Long branchId, String prefixedId) {
        Employee emp = new Employee();
        emp.setEmployeeId(prefixedId);
        emp.setDeviceEmployeeNo(person.employeeNo.trim());
        emp.setTenantId(tenantId);
        emp.setBranchId(branchId);
        emp.setEmploymentStatus(Employee.EmploymentStatus.ACTIVE);

        String fullName = StringUtils.hasText(person.name) ? person.name.trim() : person.employeeNo;
        String[] parts = fullName.split("\\s+", 2);
        emp.setFirstName(parts.length > 0 && !parts[0].isEmpty() ? parts[0] : fullName);
        emp.setLastName(parts.length > 1 ? parts[1] : "-");

        if (StringUtils.hasText(person.gender)) {
            emp.setGender(person.gender.toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(person.beginTime)) {
            emp.setHireDate(parseLocalDate(person.beginTime));
        }
        if (emp.getHireDate() == null) {
            emp.setHireDate(LocalDate.now());
        }
        return emp;
    }

    static String resolveBranchPrefix(Branch branch) {
        if (branch == null) {
            return "BR";
        }
        if (StringUtils.hasText(branch.getCode())) {
            return sanitizePrefix(branch.getCode());
        }
        return sanitizePrefix(branch.getName());
    }

    private void enforceEmployeeQuota(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        tenantRepository.findById(tenantId).ifPresent(tenant -> {
            Integer max = tenant.getMaxEmployees();
            if (max == null || max <= 0) {
                return;
            }
            long current = employeeRepository.countByTenantId(tenantId);
            if (current >= max) {
                throw new BadRequestException(
                        "Employee limit reached for this tenant (" + max + "). Upgrade the subscription to add more.");
            }
        });
    }

    static String sanitizePrefix(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "BR";
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "");
        if (cleaned.isEmpty()) {
            return "BR";
        }
        return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    static String buildPrefixedEmployeeId(String prefix, String deviceEmployeeNo) {
        String p = sanitizePrefix(prefix);
        String no = deviceEmployeeNo == null ? "" : deviceEmployeeNo.trim();
        return p + "-" + no;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long resolveTenantId(List<DeviceConfig> devices) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) return tenantId;
        return devices.stream()
                .map(DeviceConfig::getTenantId)
                .filter(id -> id != null)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("tenantId could not be resolved for import"));
    }

    private Long resolveIsapiDeviceId(DeviceConfig device) {
        if (!StringUtils.hasText(device.getDeviceId())) return null;
        try {
            return Long.valueOf(device.getDeviceId().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeName(String name) {
        if (!StringUtils.hasText(name)) return "";
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private HttpHeaders buildHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (StringUtils.hasText(username)) {
            String credentials = username + ":" + (password != null ? password : "");
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        }
        return headers;
    }

    private String buildBaseUrl(DeviceConfig device) {
        String ip = device.getDeviceIp().trim();
        int port = (device.getDevicePort() != null && device.getDevicePort() > 0)
                ? device.getDevicePort() : 80;
        if (port == 80) return "http://" + ip;
        if (port == 443) return "https://" + ip;
        return "http://" + ip + ":" + port;
    }

    private String decryptPassword(String passwordEncrypted) {
        if (!StringUtils.hasText(passwordEncrypted)) return "";
        try {
            return encryptionUtil.decrypt(passwordEncrypted);
        } catch (Exception ex) {
            log.warn("Could not decrypt device password – using value as-is: {}", ex.getMessage());
            return passwordEncrypted;
        }
    }

    private LocalDate parseLocalDate(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e2) {
                log.warn("Could not parse date '{}' from device – leaving null", dateTimeStr);
                return null;
            }
        }
    }

    private static final class AggregatedPerson {
        private String employeeNo;
        private String name;
        private String gender;
        private String beginTime;
        private boolean conflict;
        private String conflictReason;
        private Long employeePk;
        private final Set<Long> deviceConfigIds = new LinkedHashSet<>();
    }

    private record FetchedPerson(String employeeNo, String name, String gender, String beginTime) {}

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class IsapiPersonDto {
        private String employeeNo;
        private String name;
        private String userType;
        private String gender;
        private String beginTime;
        private String endTime;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class IsapiDeviceUserDto {
        private Long id;
        private String employeeNo;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class IsapiFaceDto {
        private String fpid;
        private String faceUrl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class IsapiFaceDownloadDto {
        private Long id;
        private String employeeNo;
        private String status;
        private String message;
        private String faceUrl;
        private String imageBase64;
    }
}
