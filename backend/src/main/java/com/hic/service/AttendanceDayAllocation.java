package com.hic.service;

import com.hic.model.AttendanceLog;
import com.hic.util.ShiftTypes;
import org.springframework.beans.BeanUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Daily calculation projection only. Source sessions and detailed reports remain intact. */
public final class AttendanceDayAllocation {
    private AttendanceDayAllocation() {}

    public static LocalDate workDate(AttendanceLog log) {
        if (log.getAnprWorkDate() != null) return log.getAnprWorkDate();
        return log.getCheckInTime() == null ? null : log.getCheckInTime().toLocalDate();
    }

    public static Set<LocalDate> affectedDates(AttendanceLog log, String shift) {
        return log.getAnprWorkDate() != null ? Set.of(log.getAnprWorkDate())
                : affectedDates(log.getCheckInTime(), log.getCheckOutTime(), shift);
    }

    public static List<AttendanceLog> forDay(List<AttendanceLog> logs, LocalDate day,
            Function<LocalDate, String> fallbackShift) {
        var result = new ArrayList<AttendanceLog>();
        for (var log : logs) {
            var entry = log.getCheckInTime();
            var exit = log.getCheckOutTime();
            if (entry == null) continue;
            String shift = log.getShiftType();
            if (shift == null || shift.isBlank()) shift = fallbackShift.apply(entry.toLocalDate());
            if (log.getAnprWorkDate() != null || !ShiftTypes.isFlexible(shift) || exit == null || !exit.isAfter(entry)) {
                if (day.equals(workDate(log))) result.add(log);
                continue;
            }
            var from = entry.isBefore(day.atStartOfDay()) ? day.atStartOfDay() : entry;
            var to = exit.isAfter(day.plusDays(1).atStartOfDay()) ? day.plusDays(1).atStartOfDay() : exit;
            if (!to.isAfter(from)) continue;
            var portion = new AttendanceLog();
            BeanUtils.copyProperties(log, portion);
            portion.setCheckInTime(from);
            portion.setCheckOutTime(to);
            portion.setShiftType(shift);
            portion.setLastEntryTime(null);
            // Difference of cumulative whole minutes preserves the interval total, including seconds.
            portion.setAllocatedWorkedMinutes(Math.toIntExact(java.time.Duration.between(entry, to).toMinutes()
                    - java.time.Duration.between(entry, from).toMinutes()));
            result.add(portion);
        }
        return result;
    }

    public static Set<LocalDate> affectedDates(LocalDateTime entry, LocalDateTime exit, String shift) {
        var dates = new LinkedHashSet<LocalDate>();
        if (entry == null) return dates;
        dates.add(entry.toLocalDate());
        if (ShiftTypes.isFlexible(shift) && exit != null && exit.isAfter(entry)) {
            for (var day = entry.toLocalDate().plusDays(1); day.atStartOfDay().isBefore(exit); day = day.plusDays(1)) {
                dates.add(day);
            }
        }
        return dates;
    }
}
