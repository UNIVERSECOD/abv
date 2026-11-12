package com.abv.hrerpisapi.service;

import com.abv.hrerpisapi.controller.DeviceUserController.DeviceUserCreateRequest;
import com.abv.hrerpisapi.controller.DeviceUserController.DeviceUserResponse;
import com.abv.hrerpisapi.controller.DeviceUserController.DeviceUserSyncResponse;
import com.abv.hrerpisapi.controller.DeviceUserController.DeviceUserUpdateRequest;
import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.entity.DeviceUserEntity;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.dao.repository.DeviceUserRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import com.abv.hrerpisapi.device.client.IsapiClient.UserOperationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceUserService {

    private final DeviceRepository deviceRepository;
    private final DeviceUserRepository deviceUserRepository;
    private final IsapiClient isapiClient;
    private final FaceImageNormalizer faceImageNormalizer;

    public DeviceUserResponse createDeviceUser(Long deviceId, DeviceUserCreateRequest request) {
        log.info("ActionLog.deviceUser.create.started deviceId={} employeeNo={}", deviceId, request.employeeNo());
        DeviceEntity device = requireDevice(deviceId);

        Optional<DeviceUserEntity> existingOpt = deviceUserRepository.findByEmployeeNo(request.employeeNo());
        if (existingOpt.isPresent()) {
            DeviceUserEntity existing = existingOpt.get();
            log.info("ActionLog.deviceUser.create.foundExisting userId={} employeeNo={}", existing.getId(), existing.getEmployeeNo());
            if (request.name() != null) existing.setName(request.name());
            if (request.userType() != null) existing.setUserType(request.userType());
            if (request.gender() != null) existing.setGender(request.gender());
            if (request.beginTime() != null) existing.setBeginTime(parseDateTime(request.beginTime()));
            if (request.endTime() != null) existing.setEndTime(parseDateTime(request.endTime()));
            existing.setSyncedToDevice(false);
            existing = deviceUserRepository.save(existing);
            syncUserToDevice(deviceId, existing.getId());
            return toResponse(existing);
        }

        log.info("ActionLog.deviceUser.create.validated deviceId={} employeeNo={}", deviceId, request.employeeNo());
        DeviceUserEntity entity = new DeviceUserEntity();
        entity.setEmployeeNo(request.employeeNo());
        entity.setName(request.name());
        entity.setUserType(request.userType() != null ? request.userType() : "normal");
        entity.setGender(request.gender());
        entity.setBeginTime(parseDateTime(request.beginTime()));
        entity.setEndTime(parseDateTime(request.endTime()));
        entity.setSyncedToDevice(false);
        DeviceUserEntity saved = deviceUserRepository.save(entity);
        log.info("ActionLog.deviceUser.create.saved deviceId={} userId={} employeeNo={}", deviceId, saved.getId(), saved.getEmployeeNo());

        try {
            UserOperationResult result = isapiClient.addDeviceUser(
                    device, saved.getEmployeeNo(), saved.getName(), saved.getUserType(),
                    saved.getGender(), saved.getBeginTime(), saved.getEndTime());
            if (result.success()) {
                saved.setSyncedToDevice(true);
                saved.setLastSyncTime(LocalDateTime.now());
                saved = deviceUserRepository.save(saved);
                log.info("ActionLog.deviceUser.create.ended deviceId={} userId={} employeeNo={} synced=true",
                        deviceId, saved.getId(), saved.getEmployeeNo());
            } else {
                log.warn("ActionLog.deviceUser.create.syncFailed deviceId={} userId={} employeeNo={} statusCode={} response={}",
                        deviceId, saved.getId(), saved.getEmployeeNo(), result.statusCode(), result.responseSnippet());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Sync failed for device " + deviceId + ": " + result.responseSnippet());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.create.syncError deviceId={} userId={} employeeNo={} error={}",
                    deviceId, saved.getId(), saved.getEmployeeNo(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Sync error for device " + deviceId + ": " + e.getMessage());
        }

        if (saved.isSyncedToDevice() && saved.getFaceDataUrl() != null && !saved.getFaceDataUrl().isBlank()) {
            uploadFaceByUrl(device, saved);
        }

        return toResponse(saved);
    }

    public DeviceUserResponse getDeviceUser(Long deviceId, Long userId) {
        requireDevice(deviceId);
        log.info("ActionLog.deviceUser.get.started deviceId={} userId={}", deviceId, userId);
        DeviceUserResponse response = toResponse(requireDeviceUser(userId));
        log.info("ActionLog.deviceUser.get.validated faceDataUrl={} syncedToDevice={}", response.faceDataUrl(), response.syncedToDevice());
        return response;
    }

    public DeviceUserResponse updateDeviceUser(Long deviceId, Long userId, DeviceUserUpdateRequest request) {
        log.info("ActionLog.deviceUser.update.started deviceId={} userId={}", deviceId, userId);
        DeviceEntity device = requireDevice(deviceId);
        DeviceUserEntity entity = requireDeviceUser(userId);

        if (request.name() != null) entity.setName(request.name());
        if (request.userType() != null) entity.setUserType(request.userType());
        if (request.gender() != null) entity.setGender(request.gender());
        if (request.beginTime() != null) entity.setBeginTime(parseDateTime(request.beginTime()));
        if (request.endTime() != null) entity.setEndTime(parseDateTime(request.endTime()));
        if (request.faceDataUrl() != null) entity.setFaceDataUrl(request.faceDataUrl());
        entity.setSyncedToDevice(false);
        DeviceUserEntity saved = deviceUserRepository.save(entity);

        try {
            boolean exists = isapiClient.deviceUserExists(device, saved.getEmployeeNo());
            UserOperationResult result;
            if (exists) {
                result = isapiClient.updateDeviceUser(
                        device, saved.getEmployeeNo(), saved.getName(), saved.getUserType(),
                        saved.getGender(), saved.getBeginTime(), saved.getEndTime());
                if (!result.success() && isEmployeeNoNotExist(result)) {
                    log.info("ActionLog.deviceUser.update.retryAdd deviceId={} userId={} employeeNo={}",
                            deviceId, saved.getId(), saved.getEmployeeNo());
                    result = isapiClient.addDeviceUser(
                            device, saved.getEmployeeNo(), saved.getName(), saved.getUserType(),
                            saved.getGender(), saved.getBeginTime(), saved.getEndTime());
                }
            } else {
                result = isapiClient.addDeviceUser(
                        device, saved.getEmployeeNo(), saved.getName(), saved.getUserType(),
                        saved.getGender(), saved.getBeginTime(), saved.getEndTime());
            }
            if (result.success()) {
                saved.setSyncedToDevice(true);
                saved.setLastSyncTime(LocalDateTime.now());
                saved = deviceUserRepository.save(saved);
                log.info("ActionLog.deviceUser.update.ended deviceId={} userId={} employeeNo={} synced=true",
                        deviceId, saved.getId(), saved.getEmployeeNo());
            } else {
                log.warn("ActionLog.deviceUser.update.syncFailed deviceId={} userId={} employeeNo={} statusCode={} response={}",
                        deviceId, saved.getId(), saved.getEmployeeNo(), result.statusCode(), result.responseSnippet());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.update.syncError deviceId={} userId={} employeeNo={} error={}",
                    deviceId, saved.getId(), saved.getEmployeeNo(), e.getMessage());
        }

        if (saved.isSyncedToDevice() && request.faceDataUrl() != null && !request.faceDataUrl().isBlank()) {
            uploadFaceByUrl(device, saved);
        }

        return toResponse(saved);
    }

    public void deleteDeviceUser(Long deviceId, Long userId) {
        log.info("ActionLog.deviceUser.delete.started deviceId={} userId={}", deviceId, userId);
        DeviceUserEntity entity = requireDeviceUser(userId);
        deleteDeviceUserByEmployeeNo(deviceId, entity.getEmployeeNo());
        log.info("ActionLog.deviceUser.delete.ended deviceId={} userId={} employeeNo={}",
                deviceId, userId, entity.getEmployeeNo());
    }

    public void deleteDeviceUserByEmployeeNo(Long deviceId, String employeeNo) {
        deleteDeviceUserByEmployeeNo(deviceId, employeeNo, true);
    }

    public void removeReconciledUser(Long deviceId, String employeeNo) {
        deleteDeviceUserByEmployeeNo(deviceId, employeeNo, false);
    }

    private void deleteDeviceUserByEmployeeNo(Long deviceId, String employeeNo, boolean deleteCache) {
        if (!StringUtils.hasText(employeeNo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeNo is required");
        }

        String normalizedEmployeeNo = employeeNo.trim();
        log.info("ActionLog.deviceUser.deleteByEmployeeNo.started deviceId={} employeeNo={}",
                deviceId, normalizedEmployeeNo);
        DeviceEntity device = requireDevice(deviceId);

        try {
            UserOperationResult userResult = isapiClient.deleteDeviceUser(device, normalizedEmployeeNo);
            if (!userResult.success() && userResult.statusCode() != 404) {
                log.warn("ActionLog.deviceUser.delete.userFailed deviceId={} employeeNo={} statusCode={} response={}",
                        deviceId, normalizedEmployeeNo, userResult.statusCode(), userResult.responseSnippet());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "User delete failed for device " + deviceId + ": " + userResult.responseSnippet());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.delete.syncError deviceId={} employeeNo={} error={}",
                    deviceId, normalizedEmployeeNo, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Delete error for device " + deviceId + ": " + e.getMessage());
        }

        // This cache is shared by devices. Area reconciliation must preserve it and its photos.
        if (deleteCache) deviceUserRepository.findByEmployeeNo(normalizedEmployeeNo)
                .ifPresent(deviceUserRepository::delete);
        log.info("ActionLog.deviceUser.deleteByEmployeeNo.ended deviceId={} employeeNo={}",
                deviceId, normalizedEmployeeNo);
    }

    public List<DeviceUserResponse> listDeviceUsers(Long deviceId) {
        requireDevice(deviceId);
        return deviceUserRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Live pull of all persons enrolled on the physical device (not local DB cache).
     */
    public List<DevicePersonFromDevice> fetchUsersFromDevice(Long deviceId) {
        DeviceEntity device = requireDevice(deviceId);
        log.info("ActionLog.deviceUser.fetchFromDevice.started deviceId={}", deviceId);
        try {
            List<IsapiClient.DevicePersonInfo> persons = isapiClient.searchAllDeviceUsers(device);
            List<DevicePersonFromDevice> mapped = persons.stream()
                    .map(p -> new DevicePersonFromDevice(
                            p.employeeNo(), p.name(), p.userType(), p.gender(),
                            p.beginTime(), p.endTime()))
                    .toList();
            log.info("ActionLog.deviceUser.fetchFromDevice.ended deviceId={} count={}", deviceId, mapped.size());
            return mapped;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("ActionLog.deviceUser.fetchFromDevice.failed deviceId={} error={}", deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to fetch users from device: " + e.getMessage());
        }
    }

    public List<IsapiClient.DevicePersonInfo> reconciliationSnapshot(Long deviceId) {
        try {
            return isapiClient.searchDeviceUsersSnapshot(requireDevice(deviceId));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Complete device user list unavailable");
        }
    }

    public record DevicePersonFromDevice(
            String employeeNo,
            String name,
            String userType,
            String gender,
            String beginTime,
            String endTime
    ) {
    }

    /**
     * Live pull of all face-library records on the physical device (FPID + faceURL).
     * FPID matches UserInfo.employeeNo on Hikvision Access Control terminals.
     */
    public List<DeviceFaceFromDevice> fetchFacesFromDevice(Long deviceId) {
        DeviceEntity device = requireDevice(deviceId);
        log.info("ActionLog.deviceUser.fetchFacesFromDevice.started deviceId={}", deviceId);
        try {
            List<IsapiClient.DeviceFaceRecord> faces = isapiClient.searchAllFaceRecords(device);
            List<DeviceFaceFromDevice> mapped = faces.stream()
                    .map(f -> new DeviceFaceFromDevice(f.fpid(), f.faceUrl()))
                    .toList();
            log.info("ActionLog.deviceUser.fetchFacesFromDevice.ended deviceId={} count={}",
                    deviceId, mapped.size());
            return mapped;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("ActionLog.deviceUser.fetchFacesFromDevice.failed deviceId={} error={}",
                    deviceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to fetch faces from device: " + e.getMessage());
        }
    }

    public record DeviceFaceFromDevice(String fpid, String faceUrl) {
    }

    /**
     * Downloads a face image from the device by person ID (employeeNo / FPID).
     * Does not require a local device_users row — used by HR employee import.
     */
    public DeviceUserFaceSyncResponse downloadFaceByEmployeeNo(Long deviceId, String employeeNo) {
        DeviceEntity device = requireDevice(deviceId);
        if (employeeNo == null || employeeNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeNo is required");
        }
        String personId = employeeNo.trim();
        log.info("ActionLog.deviceUser.face.downloadByNo.started deviceId={} employeeNo={}",
                deviceId, personId);

        try {
            Optional<String> faceUrlOpt = isapiClient.findFaceUrlByEmployeeNo(device, personId);
            String faceUrl = faceUrlOpt.orElse(null);
            Optional<byte[]> imageOpt = Optional.empty();

            if (faceUrl != null && !faceUrl.isBlank()) {
                imageOpt = isapiClient.downloadFaceImage(device, faceUrl);
            }
            if (imageOpt.isEmpty()) {
                imageOpt = isapiClient.downloadFaceImageByEmployeeNo(device, personId);
            }
            if (imageOpt.isEmpty()) {
                String status = faceUrl == null ? "NOT_FOUND" : "FAILED";
                String message = faceUrl == null
                        ? "No face image found in device FDLib"
                        : "Face URL found but image could not be downloaded";
                return new DeviceUserFaceSyncResponse(null, personId, status, message, faceUrl, null);
            }

            String base64Image = Base64.getEncoder().encodeToString(imageOpt.get());
            log.info("ActionLog.deviceUser.face.downloadByNo.ended deviceId={} employeeNo={} bytes={}",
                    deviceId, personId, imageOpt.get().length);
            return new DeviceUserFaceSyncResponse(
                    null,
                    personId,
                    "SUCCESS",
                    "Face image downloaded from device",
                    faceUrl,
                    base64Image
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DeviceUserFaceSyncResponse(
                    null,
                    personId,
                    "FAILED",
                    "Face download error: " + e.getMessage(),
                    null,
                    null
            );
        }
    }

    public DeviceUserSyncResponse syncUserToDevice(Long deviceId, Long userId) {
        log.info("ActionLog.deviceUser.sync.started deviceId={} userId={}", deviceId, userId);
        DeviceEntity device = requireDevice(deviceId);
        DeviceUserEntity entity = requireDeviceUser(userId);

        try {
            boolean exists = isapiClient.deviceUserExists(device, entity.getEmployeeNo());
            UserOperationResult result;
            if (exists) {
                result = isapiClient.updateDeviceUser(device, entity.getEmployeeNo(), entity.getName(),
                        entity.getUserType(), entity.getGender(), entity.getBeginTime(), entity.getEndTime());
                if (!result.success() && isEmployeeNoNotExist(result)) {
                    log.info("ActionLog.deviceUser.sync.retryAdd deviceId={} userId={} employeeNo={}",
                            deviceId, entity.getId(), entity.getEmployeeNo());
                    result = isapiClient.addDeviceUser(device, entity.getEmployeeNo(), entity.getName(),
                            entity.getUserType(), entity.getGender(), entity.getBeginTime(), entity.getEndTime());
                }
            } else {
                result = isapiClient.addDeviceUser(device, entity.getEmployeeNo(), entity.getName(),
                        entity.getUserType(), entity.getGender(), entity.getBeginTime(), entity.getEndTime());
            }

            if (result.success()) {
                entity.setSyncedToDevice(true);
                entity.setLastSyncTime(LocalDateTime.now());
                entity = deviceUserRepository.save(entity);
                log.info("ActionLog.deviceUser.sync.ended deviceId={} userId={} employeeNo={} synced=true",
                        deviceId, entity.getId(), entity.getEmployeeNo());
                return toSyncResponse(entity, "SUCCESS", "User synced successfully");
            } else {
                log.warn("ActionLog.deviceUser.sync.failed deviceId={} userId={} employeeNo={} statusCode={} response={}",
                        deviceId, entity.getId(), entity.getEmployeeNo(), result.statusCode(), result.responseSnippet());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Sync failed for device " + deviceId + ": " + result.responseSnippet());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.sync.error deviceId={} userId={} employeeNo={} error={}",
                    deviceId, entity.getId(), entity.getEmployeeNo(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Sync error for device " + deviceId + ": " + e.getMessage());
        }
    }

    private boolean isEmployeeNoNotExist(UserOperationResult result) {
        if (result.responseSnippet() == null) return false;
        String snippet = result.responseSnippet().toLowerCase();
        return snippet.contains("employeenonotexist") || snippet.contains("employeeNoNotExist".toLowerCase());
    }

    public DeviceUserResponse uploadFaceData(Long deviceId, Long userId, MultipartFile file) {
        log.info("ActionLog.deviceUser.face.upload.started deviceId={} userId={}", deviceId, userId);
        DeviceEntity device = requireDevice(deviceId);
        DeviceUserEntity entity = requireDeviceUser(userId);

        try {
            byte[] imageBytes = file.getBytes();
            FaceImageNormalizer.NormalizedFaceImage normalized = faceImageNormalizer.normalize(imageBytes);
            log.info("ActionLog.deviceUser.face.normalized deviceId={} userId={} employeeNo={} sourceBytes={} targetBytes={} width={} height={}",
                    deviceId, entity.getId(), entity.getEmployeeNo(), imageBytes.length, normalized.bytes().length,
                    normalized.width(), normalized.height());
            UserOperationResult result = isapiClient.uploadFaceToFDLib(
                    device, entity.getEmployeeNo(), normalized.bytes());
            if (result.success()) {
                log.info("ActionLog.deviceUser.face.upload.ended deviceId={} userId={} employeeNo={} synced=true",
                        deviceId, entity.getId(), entity.getEmployeeNo());
            } else {
                log.warn("ActionLog.deviceUser.face.upload.failed deviceId={} userId={} employeeNo={} statusCode={} response={}",
                        deviceId, entity.getId(), entity.getEmployeeNo(), result.statusCode(), result.responseSnippet());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Face upload failed: " + result.responseSnippet());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.face.upload.error deviceId={} userId={} employeeNo={} error={}",
                    deviceId, entity.getId(), entity.getEmployeeNo(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Face upload error: " + e.getMessage());
        }

        return toResponse(entity);
    }

    public DeviceUserFaceDeleteResponse deleteFaceData(Long deviceId, Long userId) {
        log.info("ActionLog.deviceUser.face.delete.started deviceId={} userId={}", deviceId, userId);
        DeviceEntity device = requireDevice(deviceId);
        DeviceUserEntity entity = requireDeviceUser(userId);

        try {
            UserOperationResult result = isapiClient.deleteFaceFromFDLib(device, entity.getEmployeeNo());
            boolean treatAsSuccess = result.success() || result.statusCode() == 404;
            if (treatAsSuccess) {
                entity.setFaceDataUrl(null);
                deviceUserRepository.save(entity);
                log.info("ActionLog.deviceUser.face.delete.ended deviceId={} userId={} employeeNo={} statusCode={}",
                        deviceId, userId, entity.getEmployeeNo(), result.statusCode());
                return new DeviceUserFaceDeleteResponse(
                        entity.getId(),
                        entity.getEmployeeNo(),
                        "SUCCESS",
                        result.statusCode() == 404 ? "Face data already missing on device" : "Face data deleted"
                );
            }
            log.warn("ActionLog.deviceUser.face.delete.failed deviceId={} userId={} employeeNo={} statusCode={} response={}",
                    deviceId, userId, entity.getEmployeeNo(), result.statusCode(), result.responseSnippet());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Face delete failed: " + result.responseSnippet());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.face.delete.error deviceId={} userId={} employeeNo={} error={}",
                    deviceId, userId, entity.getEmployeeNo(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Face delete error: " + e.getMessage());
        }
    }

    public DeviceUserFaceSyncResponse syncFaceFromDevice(Long deviceId, Long userId) {
        DeviceEntity device = requireDevice(deviceId);
        DeviceUserEntity entity = requireDeviceUser(userId);

        try {
            Optional<String> faceUrlOpt = isapiClient.findFaceUrlByEmployeeNo(device, entity.getEmployeeNo());
            if (faceUrlOpt.isEmpty()) {
                return new DeviceUserFaceSyncResponse(
                        entity.getId(),
                        entity.getEmployeeNo(),
                        "NOT_FOUND",
                        "No face image found in device FDLib",
                        null,
                        null
                );
            }

            String faceUrl = faceUrlOpt.get();
            Optional<byte[]> imageOpt = isapiClient.downloadFaceImage(device, faceUrl);
            if (imageOpt.isEmpty()) {
                imageOpt = isapiClient.downloadFaceImageByEmployeeNo(device, entity.getEmployeeNo());
            }
            if (imageOpt.isEmpty()) {
                return new DeviceUserFaceSyncResponse(
                        entity.getId(),
                        entity.getEmployeeNo(),
                        "FAILED",
                        "Face URL found but image could not be downloaded",
                        faceUrl,
                        null
                );
            }

            String base64Image = Base64.getEncoder().encodeToString(imageOpt.get());
            return new DeviceUserFaceSyncResponse(
                    entity.getId(),
                    entity.getEmployeeNo(),
                    "SUCCESS",
                    "Face image downloaded from device",
                    faceUrl,
                    base64Image
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DeviceUserFaceSyncResponse(
                    entity.getId(),
                    entity.getEmployeeNo(),
                    "FAILED",
                    "Face sync error: " + e.getMessage(),
                    null,
                    null
            );
        }
    }

    private void uploadFaceByUrl(DeviceEntity device, DeviceUserEntity entity) {
        try {
            UserOperationResult faceResult = isapiClient.uploadFaceByUrl(
                    device, entity.getEmployeeNo(), entity.getFaceDataUrl());
            if (faceResult.success()) {
                log.info("ActionLog.deviceUser.face.url.upload.ended deviceId={} userId={} employeeNo={}",
                        device.getId(), entity.getId(), entity.getEmployeeNo());
            } else {
                log.warn("ActionLog.deviceUser.face.url.upload.failed deviceId={} userId={} employeeNo={} statusCode={} response={}",
                        device.getId(), entity.getId(), entity.getEmployeeNo(),
                        faceResult.statusCode(), faceResult.responseSnippet());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("ActionLog.deviceUser.face.url.upload.error deviceId={} userId={} employeeNo={} error={}",
                    device.getId(), entity.getId(), entity.getEmployeeNo(), e.getMessage());
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid datetime format, expected ISO 8601 (e.g. 2026-01-01T00:00:00): " + value);
        }
    }

    private DeviceEntity requireDevice(Long deviceId) {
        DeviceEntity device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (device.isAnpr()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ANPR camera does not support device users");
        }
        return device;
    }

    private DeviceUserEntity requireDeviceUser(Long userId) {
        return deviceUserRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device user not found"));
    }

    private DeviceUserResponse toResponse(DeviceUserEntity entity) {
        return new DeviceUserResponse(
                entity.getId(),
                entity.getEmployeeNo(),
                entity.getName(),
                entity.getUserType(),
                entity.getGender(),
                entity.getBeginTime(),
                entity.getEndTime(),
                entity.getFaceDataUrl(),
                entity.isSyncedToDevice(),
                entity.getLastSyncTime(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private DeviceUserSyncResponse toSyncResponse(DeviceUserEntity entity, String status, String message) {
        return new DeviceUserSyncResponse(
                entity.getId(),
                entity.getEmployeeNo(),
                entity.isSyncedToDevice(),
                entity.getLastSyncTime(),
                status,
                message);
    }

    public record DeviceUserFaceSyncResponse(
            Long id,
            String employeeNo,
            String status,
            String message,
            String faceUrl,
            String imageBase64
    ) {
    }

    public record DeviceUserFaceDeleteResponse(
            Long id,
            String employeeNo,
            String status,
            String message
    ) {
    }
}
