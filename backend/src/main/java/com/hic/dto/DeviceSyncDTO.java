package com.hic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public class DeviceSyncDTO {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceConfigDTO {
        private Long id;
        private String deviceId;
        private String deviceName;
        private String deviceIp;
        private Integer devicePort;
        private String username;
        private String password;
        private Long branchId;
        private Long doorId;
        private String doorRole;
        private String status;
        private boolean online;
        private LocalDateTime lastSyncTime;
        private Boolean anpr;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncResultDTO {
        private boolean success;
        private String message;
        private Integer recordsSynced;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncHistoryDTO {
        private Long id;
        private String deviceId;
        private LocalDateTime syncStartTime;
        private LocalDateTime syncEndTime;
        private Integer recordsSynced;
        private String syncStatus;
        private String errorMessage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceEnabledDTO {
        private boolean enabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceRuntimeDTO {
        private Long id;
        private boolean enabled;
        private boolean running;
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceStatusDTO {
        private Long id;
        private boolean online;
        private int statusCode;
        private String responseSnippet;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IsapiDeviceResponse {
        private Long id;
        private String ip;
        private String username;
        private String name;
        private boolean enabled;
        private boolean running;
        private boolean online;
        private OffsetDateTime lastSyncTime;
        private Boolean anpr;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DeviceUpsertRequest {
        private String ip;
        private String username;
        private String password;
        private String name;
        private Boolean enabled;
        private Long branchId;
        private Boolean anpr;
        private String doorRole;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IsapiDeviceUpsertRequest {
        private String ip;
        private String username;
        private String password;
        private String name;
        private Boolean enabled;
        private Boolean anpr;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IsapiDeviceEnabledRequest {
        private boolean enabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IsapiDeviceRuntimeResponse {
        private Long id;
        private boolean enabled;
        private boolean running;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IsapiDeviceStatusResponse {
        private Long id;
        private boolean online;
        private int statusCode;
        private String responseSnippet;
    }
}
