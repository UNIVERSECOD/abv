package com.hic.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

public class AttendanceLogSyncDTO {

    @Data
    @NoArgsConstructor
    public static class AttendanceLogEntryDTO {
        private Long id;
        private Long deviceId;
        private String employeeNo;
        private String terminalName;
        private OffsetDateTime punchTime;
        private Long rawEventId;
        private String firstName;
        private String lastName;
        private String deviceName;
        /** ENTRY, EXIT or BOTH from device_configs.door_role */
        private String doorRole;
        private String verificationMethod;

        public AttendanceLogEntryDTO(Long id, Long deviceId, String employeeNo, OffsetDateTime punchTime, Long rawEventId) {
            this.id = id;
            this.deviceId = deviceId;
            this.employeeNo = employeeNo;
            this.punchTime = punchTime;
            this.rawEventId = rawEventId;
        }

        public AttendanceLogEntryDTO(Long id, Long deviceId, String employeeNo, OffsetDateTime punchTime, Long rawEventId, String firstName, String lastName) {
            this.id = id;
            this.deviceId = deviceId;
            this.employeeNo = employeeNo;
            this.punchTime = punchTime;
            this.rawEventId = rawEventId;
            this.firstName = firstName;
            this.lastName = lastName;
        }

        public AttendanceLogEntryDTO(Long id, Long deviceId, String employeeNo, OffsetDateTime punchTime, Long rawEventId,
                                     String firstName, String lastName, String deviceName, String doorRole) {
            this.id = id;
            this.deviceId = deviceId;
            this.employeeNo = employeeNo;
            this.punchTime = punchTime;
            this.rawEventId = rawEventId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.deviceName = deviceName;
            this.doorRole = doorRole;
        }
    }
}
