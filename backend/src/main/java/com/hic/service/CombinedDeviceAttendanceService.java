package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO.AttendanceLogEntryDTO;
import com.hic.model.AttendanceLog;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogRepository;
import com.hic.util.AppTimeZone;
import com.hic.util.ShiftTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/** Calendar-day first/last aggregation, called inside the tenant-serialized sync transaction. */
@Service
@RequiredArgsConstructor
public class CombinedDeviceAttendanceService {
    private final AttendanceLogRepository logs;
    private final EmployeeShiftResolver shifts;

    public record Result(boolean changed, boolean closed, boolean skipped) {
        static final Result UNCHANGED = new Result(false, false, false);
        static final Result SKIPPED = new Result(false, false, true);
    }

    public Result accept(Long tenantId, Employee employee, Long areaId, AttendanceLogEntryDTO punch) {
        if (tenantId == null || areaId == null || !tenantId.equals(employee.getTenantId())
                || punch.getId() == null || punch.getDeviceId() == null || punch.getPunchTime() == null) {
            return Result.SKIPPED;
        }
        LocalDateTime time = AppTimeZone.toLocalDateTime(punch.getPunchTime());
        var day = time.toLocalDate();
        AttendanceLog session = logs.findByTenantIdAndEmployeeIdAndCombinedAreaIdAndCombinedWorkDate(
                tenantId, employee.getId(), areaId, day).orElse(null);
        if (session != null && Boolean.TRUE.equals(session.getManualOverride())) return Result.SKIPPED;

        if (session == null) {
            var shift = shifts.resolve(employee, day);
            // Unassigned/unknown schedules also cannot safely imply a scheduled working day.
            String type = ShiftTypes.canonical(shift.shiftType());
            if (!ShiftTypes.STANDARD.equals(type) && !ShiftTypes.NIGHT.equals(type)) return Result.SKIPPED;
            if (logs.findByTenantIdAndEntryPunchId(tenantId, punch.getId()).isPresent()
                    || logs.existsByTenantIdAndExitPunchId(tenantId, punch.getId())) return Result.SKIPPED;
            session = new AttendanceLog();
            session.setTenantId(tenantId);
            session.setEmployeeId(employee.getId());
            session.setCombinedAreaId(areaId);
            session.setCombinedWorkDate(day);
            session.setCheckInTime(time);
            session.setEntryPunchId(punch.getId());
            session.setEntryMethod(punch.getVerificationMethod());
            session.setDeviceId(punch.getDeviceId().toString());
            session.setDoorId(punch.getDeviceId() + ":null");
            session.setEventType("COMBINED_SESSION");
            session.setVerificationMethod("ISAPI_PUNCH");
            session.setShiftType(shift.shiftType());
            session.setTimetableId(shift.timetableId());
        } else {
            if (Objects.equals(punch.getId(), session.getEntryPunchId())
                    || Objects.equals(punch.getId(), session.getExitPunchId())) return Result.UNCHANGED;
            if (time.isBefore(session.getCheckInTime())) {
                // A delayed earlier scan becomes the entry; the former lone entry becomes the exit.
                if (session.getCheckOutTime() == null) {
                    session.setCheckOutTime(session.getCheckInTime());
                    session.setExitPunchId(session.getEntryPunchId());
                    session.setExitMethod(session.getEntryMethod());
                    session.setDoorId(punch.getDeviceId() + ":" + session.getDeviceId());
                } else {
                    String exitDevice = session.getDoorId().split(":", 2)[1];
                    session.setDoorId(punch.getDeviceId() + ":" + exitDevice);
                }
                session.setCheckInTime(time);
                session.setEntryPunchId(punch.getId());
                session.setEntryMethod(punch.getVerificationMethod());
                session.setDeviceId(punch.getDeviceId().toString());
            } else if (time.isAfter(session.getCheckInTime())
                    && (session.getCheckOutTime() == null || time.isAfter(session.getCheckOutTime()))) {
                session.setCheckOutTime(time);
                session.setExitPunchId(punch.getId());
                session.setExitMethod(punch.getVerificationMethod());
                session.setDoorId(session.getDeviceId() + ":" + punch.getDeviceId());
            } else {
                return Result.UNCHANGED;
            }
        }
        session.setStatus(session.getCheckOutTime() != null ? "CLOSED"
                : day.isBefore(AppTimeZone.today()) ? "MISSING_EXIT" : "OPEN");
        logs.save(session);
        return new Result(true, session.getCheckOutTime() != null, false);
    }
}
