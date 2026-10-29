package com.hic.service;

import com.hic.model.DailyAttendanceSummary.AttendanceStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceTimeCalculatorTest {

    private final AttendanceTimeCalculator calculator = new AttendanceTimeCalculator();
    private final LocalDate date = LocalDate.of(2024, 1, 15);

    @Test
    void combinedPreviousDayMissingExitDoesNotRemainOpenUnderNightShiftGrace() {
        var log = new com.hic.model.AttendanceLog();
        log.setCheckInTime(date.atTime(23, 50));
        log.setCombinedAreaId(10L); log.setCombinedWorkDate(date);
        var inference = new AttendanceInferenceService().inferDay(List.of(log), date);
        var schedule = new AttendanceScheduleResolver.DaySchedule(1L, "NIGHT", true,
                LocalTime.of(20, 0), LocalTime.of(8, 0), 0, 0, 0);
        try (var clock = org.mockito.Mockito.mockStatic(com.hic.util.AppTimeZone.class)) {
            clock.when(com.hic.util.AppTimeZone::now).thenReturn(date.plusDays(1).atTime(1, 0));
            clock.when(com.hic.util.AppTimeZone::today).thenReturn(date.plusDays(1));
            assertThat(calculator.calculate(date, inference, schedule).status()).isEqualTo(AttendanceStatus.MISSING_EXIT);
        }
    }

    @Test
    void entryAtToleranceBoundary_isNormal() {
        var result = calculator.calculate(
                date,
                inference(date.atTime(8, 30), date.atTime(18, 0)),
                standardSchedule(30, 30)
        );

        assertThat(result.lateMinutes()).isZero();
        assertThat(result.status()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    void entryAfterToleranceBoundary_isLateByExcessMinutes() {
        var result = calculator.calculate(
                date,
                inference(date.atTime(8, 31), date.atTime(18, 0)),
                standardSchedule(30, 30)
        );

        assertThat(result.lateMinutes()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(AttendanceStatus.LATE);
    }

    @Test
    void exitAtToleranceBoundary_isNormal() {
        var result = calculator.calculate(
                date,
                inference(date.atTime(8, 0), date.atTime(17, 30)),
                standardSchedule(30, 30)
        );

        assertThat(result.earlyLeaveMinutes()).isZero();
        assertThat(result.status()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    void exitBeforeToleranceBoundary_isEarlyByExcessMinutes() {
        var result = calculator.calculate(
                date,
                inference(date.atTime(8, 0), date.atTime(17, 29)),
                standardSchedule(30, 30)
        );

        assertThat(result.earlyLeaveMinutes()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(AttendanceStatus.EARLY_LEAVE);
    }

    @Test
    void dayOffWithoutPunches_isNotAbsent() {
        AttendanceInferenceService.AttendanceInference inference =
                new AttendanceInferenceService.AttendanceInference(null, null, 0, false, List.of());
        AttendanceScheduleResolver.DaySchedule dayOff = new AttendanceScheduleResolver.DaySchedule(
                1L, "STANDARD", false, null, null, 0, 30, 30);

        var result = calculator.calculate(date, inference, dayOff);

        assertThat(result.status()).isEqualTo(AttendanceStatus.DAY_OFF);
    }

    @Test
    void expiredOpenSession_isMissingExitWithoutEarlyLeave() {
        AttendanceInferenceService.AttendanceInference inference =
                new AttendanceInferenceService.AttendanceInference(
                        date.atTime(8, 0),
                        date.atTime(12, 0),
                        240,
                        true,
                        List.of()
                );

        var result = calculator.calculate(date, inference, standardSchedule(30, 30));

        assertThat(result.earlyLeaveMinutes()).isZero();
        assertThat(result.status()).isEqualTo(AttendanceStatus.MISSING_EXIT);
    }

    private AttendanceScheduleResolver.DaySchedule standardSchedule(int lateTolerance, int earlyTolerance) {
        return new AttendanceScheduleResolver.DaySchedule(
                1L,
                "STANDARD",
                true,
                LocalTime.of(8, 0),
                LocalTime.of(18, 0),
                0,
                lateTolerance,
                earlyTolerance
        );
    }

    private AttendanceInferenceService.AttendanceInference inference(LocalDateTime entry, LocalDateTime exit) {
        int workedMinutes = (int) java.time.Duration.between(entry, exit).toMinutes();
        return new AttendanceInferenceService.AttendanceInference(
                entry,
                exit,
                workedMinutes,
                false,
                List.of(new AttendanceInferenceService.SessionSegment(entry, exit))
        );
    }
}
