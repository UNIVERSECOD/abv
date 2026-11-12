package com.hic.dto;

import com.hic.model.DailyAttendanceSummary.AttendanceStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class EmployeeAttendanceRowDTO {
    private LocalDate date;
    private OffsetDateTime checkInTime;
    private OffsetDateTime checkOutTime;
    private String entryMethod;
    private String exitMethod;
    private boolean managedAttendance;
    private Double hoursWorked;
    private Integer lateMinutes;
    private Integer earlyLeaveMinutes;
    private Integer permissionMinutes;
    private Integer creditedPermissionMinutes;
    private Boolean hasPermission;
    private AttendanceStatus status;
    private String notes;
    /** Employee shift type (e.g. FIRST_ENTRY = serbest növbə). */
    private String shiftType;
    /** All entry/exit sessions for the day (important for flexible / serbest shifts). */
    private List<AttendanceSessionDTO> sessions = new ArrayList<>();
}
