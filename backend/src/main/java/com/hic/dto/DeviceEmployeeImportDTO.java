package com.hic.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Setup-team flow: import employees from all (or selected) devices of a branch.
 * Match key is device {@code employeeNo} (person ID) — more reliable than HikCentral's name match.
 */
public class DeviceEmployeeImportDTO {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportRequest {
        /** Required: import scans devices belonging to this branch. */
        private Long branchId;

        /**
         * Optional subset of {@code device_configs.id}.
         * When null/empty, all devices of the branch are scanned.
         */
        private List<Long> deviceConfigIds;

        /**
         * When true (default), persons found on any scanned device are also written
         * to the other devices of the same branch (user record + face when available).
         * Null is treated as true.
         */
        private Boolean writeToOtherBranchDevices;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportResult {
        private Long branchId;
        private String branchName;
        private String branchPrefix;
        private int devicesScanned;
        private int devicesFailed;
        private int totalFetched;
        private int uniquePersons;
        private int created;
        private int skippedExisting;
        private int skippedTerminated;
        private int skippedConflict;
        private int crossBranchLinked;
        private int accessLinked;
        private int facesSynced;
        private int facesSkippedNoMatch;
        private int facesSkippedExisting;
        private int facesFailed;
        /** Person was created on a sibling branch device where they were missing. */
        private int writtenToOtherDevices;
        /** Sibling device already had the person (or create reported already exists). */
        private int alreadyOnOtherDevices;
        /** Failed to create the person on a sibling branch device. */
        private int otherDeviceWriteFailed;
        /** Face image pushed to a sibling branch device. */
        private int facesWrittenToOtherDevices;
        private int facesOtherDeviceFailed;
        private int errors;
        private String message;
        private List<DeviceScanStatus> deviceStatuses = new ArrayList<>();
        private List<SkippedPerson> conflicts = new ArrayList<>();
        private List<SkippedPerson> errorsDetail = new ArrayList<>();
        private List<CreatedPerson> createdPersons = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceScanStatus {
        private Long deviceConfigId;
        private String deviceName;
        private String deviceIp;
        private boolean success;
        private int userCount;
        private int faceCount;
        private int facesSynced;
        private String error;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedPerson {
        private String employeeNo;
        private String name;
        private String reason;
        private List<Long> deviceConfigIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatedPerson {
        private Long employeePk;
        private String employeeNo;
        /** Prefixed HR code stored as employeeId, e.g. BAK-1001. */
        private String employeeId;
        /** Raw device person ID. */
        private String deviceEmployeeNo;
        private Long homeBranchId;
        private String firstName;
        private String lastName;
        private List<Long> deviceConfigIds;
    }
}
