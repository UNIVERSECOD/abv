package com.hic.service;

import com.hic.model.AttendanceLog;
import com.hic.model.Employee;
import com.hic.util.AppTimeZone;
import com.hic.util.ShiftTypes;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class AttendanceSessionPolicy {

    static final int FLEXIBLE_MAX_SESSION_HOURS = 24;
    static final int MIN_SCHEDULED_SESSION_WINDOW_HOURS = 8;
    static final int SCHEDULED_CLOSE_GRACE_MINUTES = 6 * 60;
    static final int DUPLICATE_PUNCH_WINDOW_SECONDS = 60;

    private final AttendanceScheduleResolver scheduleResolver;

    public AttendanceSessionPolicy(AttendanceScheduleResolver scheduleResolver) {
        this.scheduleResolver = scheduleResolver;
    }

    public boolean isExpired(Employee employee, AttendanceLog session) {
        return isExpired(employee, session, AppTimeZone.now());
    }

    boolean isExpired(Employee employee, AttendanceLog session, LocalDateTime now) {
        if (session == null || session.getCheckInTime() == null || session.getCheckOutTime() != null) {
            return false;
        }

        LocalDateTime checkIn = session.getCheckInTime();
        LocalDateTime calendarDeadline = calendarDayDeadline(session);
        if (calendarDeadline != null) {
            return !now.isBefore(calendarDeadline);
        }
        if (ShiftTypes.isFlexible(session.getShiftType())) {
            return !now.isBefore(checkIn.plusHours(FLEXIBLE_MAX_SESSION_HOURS));
        }
        LocalDate workDate = checkIn.toLocalDate();
        AttendanceScheduleResolver.DaySchedule schedule = scheduleResolver.resolve(employee, workDate);
        return isExpired(checkIn, schedule, now);
    }

    static boolean isExpired(
            LocalDateTime checkIn,
            AttendanceScheduleResolver.DaySchedule schedule,
            LocalDateTime now
    ) {
        if (checkIn == null || schedule == null || now == null) {
            return false;
        }
        LocalDate workDate = checkIn.toLocalDate();
        LocalDateTime expiresAt;
        if (schedule.flexible() || schedule.startTime() == null || schedule.endTime() == null) {
            expiresAt = checkIn.plusHours(FLEXIBLE_MAX_SESSION_HOURS);
        } else {
            LocalDateTime scheduledStart = workDate.atTime(schedule.startTime());
            LocalDateTime scheduledEnd = workDate.atTime(schedule.endTime());
            if (!scheduledEnd.isAfter(scheduledStart)) {
                scheduledEnd = scheduledEnd.plusDays(1);
            }
            LocalDateTime scheduledCutoff = scheduledEnd.plusMinutes(SCHEDULED_CLOSE_GRACE_MINUTES);
            LocalDateTime minimumSessionCutoff = checkIn.plusHours(MIN_SCHEDULED_SESSION_WINDOW_HOURS);
            expiresAt = scheduledCutoff.isAfter(minimumSessionCutoff)
                    ? scheduledCutoff
                    : minimumSessionCutoff;
        }
        return !now.isBefore(expiresAt);
    }

    static LocalDateTime calendarDayDeadline(AttendanceLog session) {
        return session != null && session.getCombinedWorkDate() != null
                ? session.getCombinedWorkDate().plusDays(1).atStartOfDay() : null;
    }

    public boolean isDuplicateEntry(AttendanceLog openSession, LocalDateTime punchTime) {
        if (openSession == null || openSession.getCheckInTime() == null || punchTime == null) {
            return false;
        }
        long seconds = Math.abs(java.time.Duration.between(openSession.getCheckInTime(), punchTime).getSeconds());
        return seconds <= DUPLICATE_PUNCH_WINDOW_SECONDS;
    }

    public String effectiveStatus(Employee employee, AttendanceLog session) {
        if (session == null) {
            return null;
        }
        if (session.getCheckOutTime() != null) {
            return Boolean.TRUE.equals(session.getManualOverride()) ? "MANUALLY_CORRECTED" : "CLOSED";
        }
        return isExpired(employee, session) ? "MISSING_EXIT" : "OPEN";
    }
}
