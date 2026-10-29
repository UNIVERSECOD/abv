package com.hic.service;

import com.hic.model.AttendanceLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceInferenceServiceTest {

    @Test
    void laterDirectionalEntryKeepsItsShiftWindowAfterACombinedScan() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        var combined = new AttendanceLog(); combined.setCheckInTime(date.atTime(20, 0));
        combined.setCombinedAreaId(10L); combined.setCombinedWorkDate(date);
        var directional = new AttendanceLog(); directional.setCheckInTime(date.atTime(21, 0));
        var inference = new AttendanceInferenceService().inferDay(List.of(combined, directional), date);
        assertThat(inference.currentlyInside()).isTrue();
        assertThat(inference.openSessionDeadline()).isNull();
    }

    @Test
    void laterCombinedEntryUsesMidnightEvenWhenAnOlderDirectionalRowIsOpen() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        var combined = new AttendanceLog(); combined.setCheckInTime(date.atTime(21, 0));
        combined.setCombinedAreaId(10L); combined.setCombinedWorkDate(date);
        var directional = new AttendanceLog(); directional.setCheckInTime(date.atTime(20, 0));
        assertThat(new AttendanceInferenceService().inferDay(List.of(combined, directional), date).openSessionDeadline())
                .isEqualTo(date.plusDays(1).atStartOfDay());
    }

    private AttendanceInferenceService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceInferenceService();
    }

    @Test
    void sameDaySession_countsFullyOnThatDay() {
        AttendanceLog log = session(
                LocalDateTime.of(2026, 4, 26, 9, 0),
                LocalDateTime.of(2026, 4, 26, 18, 0)
        );

        var day = service.inferDay(List.of(log), LocalDate.of(2026, 4, 26));

        assertThat(day.firstEntry()).isEqualTo(LocalDateTime.of(2026, 4, 26, 9, 0));
        assertThat(day.lastExit()).isEqualTo(LocalDateTime.of(2026, 4, 26, 18, 0));
        assertThat(day.workedMinutes()).isEqualTo(9 * 60);
    }

    @Test
    void midnightCrossing_staysOnCheckInWorkDate() {
        AttendanceLog log = session(
                LocalDateTime.of(2026, 4, 26, 22, 0),
                LocalDateTime.of(2026, 4, 27, 6, 0)
        );

        var day1 = service.inferDay(List.of(log), LocalDate.of(2026, 4, 26));
        var day2 = service.inferDay(List.of(log), LocalDate.of(2026, 4, 27));

        assertThat(day1.firstEntry()).isEqualTo(LocalDateTime.of(2026, 4, 26, 22, 0));
        assertThat(day1.lastExit()).isEqualTo(LocalDateTime.of(2026, 4, 27, 6, 0));
        assertThat(day1.workedMinutes()).isEqualTo(8 * 60);

        assertThat(day2.firstEntry()).isNull();
        assertThat(day2.lastExit()).isNull();
        assertThat(day2.workedMinutes()).isZero();
    }

    @Test
    void belongsToWorkDate_usesCheckInDateOnly() {
        AttendanceLog log = session(
                LocalDateTime.of(2026, 4, 26, 22, 0),
                LocalDateTime.of(2026, 4, 27, 6, 0)
        );

        assertThat(service.belongsToWorkDate(log, LocalDate.of(2026, 4, 26))).isTrue();
        assertThat(service.belongsToWorkDate(log, LocalDate.of(2026, 4, 27))).isFalse();
    }

    @Test
    void multiSessionDay_standardUsesSpan_flexibleSumsIntervals() {
        AttendanceLog morning = session(
                LocalDateTime.of(2026, 4, 26, 9, 0),
                LocalDateTime.of(2026, 4, 26, 11, 0)
        );
        AttendanceLog midday = session(
                LocalDateTime.of(2026, 4, 26, 13, 0),
                LocalDateTime.of(2026, 4, 26, 15, 30)
        );
        AttendanceLog evening = session(
                LocalDateTime.of(2026, 4, 26, 17, 0),
                LocalDateTime.of(2026, 4, 26, 18, 0)
        );

        var day = service.inferDay(List.of(morning, midday, evening), LocalDate.of(2026, 4, 26));

        assertThat(day.intervalWorkedMinutes()).isEqualTo(330);
        assertThat(day.spanWorkedMinutes()).isEqualTo(9 * 60);
        assertThat(day.workedMinutesForShift("STANDARD")).isEqualTo(9 * 60);
        assertThat(day.workedMinutesForShift("NIGHT")).isEqualTo(9 * 60);
        assertThat(day.workedMinutesForShift("FLEXIBLE")).isEqualTo(330);
        assertThat(day.workedMinutesForShift("FIRST_ENTRY")).isEqualTo(330);
    }

    @Test
    void olderUnmatchedEntryDoesNotOverrideLaterConfirmedExit() {
        LocalDate day = LocalDate.of(2026, 4, 26);
        AttendanceLog missingExit = session(day.atTime(9, 0), null);
        AttendanceLog closed = session(day.atTime(12, 0), day.atTime(17, 0));

        var inference = service.inferDay(List.of(missingExit, closed), day);

        assertThat(inference.currentlyInside()).isFalse();
        assertThat(inference.spanWorkedMinutes()).isEqualTo(8 * 60);
        assertThat(inference.workedMinutesForShift("STANDARD")).isEqualTo(8 * 60);
        assertThat(inference.workedMinutesForShift("FLEXIBLE")).isEqualTo(5 * 60);
    }

    @Test
    void repeatedEntriesAcrossDevicesAndLaterExitProduceClosedDayWithoutChangingSource() {
        LocalDate date = LocalDate.of(2026, 4, 26);
        var first = session(date.atTime(13, 21, 38), date.atTime(13, 21, 44));
        var repeated = session(date.atTime(13, 21, 43), null);
        var stale = session(date.atTime(13, 35, 22), null);
        var closed = session(date.atTime(13, 44, 35), date.atTime(17, 41, 22));
        first.setDeviceId("entry-terminal"); stale.setDeviceId("entry-terminal"); closed.setDeviceId("exit-terminal");
        var inference = service.inferDay(List.of(stale, closed, first, repeated), date);
        assertThat(inference.currentlyInside()).isFalse();
        assertThat(inference.workedMinutesForShift("STANDARD")).isEqualTo(259);
        assertThat(inference.workedMinutesForShift("FLEXIBLE")).isEqualTo(236);
        var schedule = new AttendanceScheduleResolver.DaySchedule(1L, "STANDARD", true,
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), 0, 10, 0);
        var result = new AttendanceTimeCalculator().calculate(date, inference, schedule);
        assertThat(result.status()).isEqualTo(com.hic.model.DailyAttendanceSummary.AttendanceStatus.EARLY_LEAVE);
        assertThat(result.lateMinutes()).isEqualTo(251);
        assertThat(result.earlyLeaveMinutes()).isEqualTo(18);
        assertThat(stale.getCheckOutTime()).isNull();
        assertThat(repeated.getCheckOutTime()).isNull();
    }

    @Test
    void actualReentryAfterExitKeepsMissingExitEvenInsideDuplicateWindow() {
        LocalDate date = LocalDate.of(2026, 4, 26);
        var closed = session(date.atTime(9, 0), date.atTime(9, 0, 20));
        var reentry = session(date.atTime(9, 0, 30), null);
        assertThat(service.inferDay(List.of(closed, reentry), date).currentlyInside()).isTrue();
        var earlier = session(date.atTime(8, 0), date.atTime(8, 30));
        var inference = service.inferDay(List.of(earlier, closed, reentry), date);
        assertThat(inference.workedMinutesForShift("STANDARD")).isEqualTo(30);
    }

    @Test
    void repeatedEntryTimestampWinsButManualCorrectionIgnoresOldRepeatMetadata() {
        LocalDate date = LocalDate.of(2026, 4, 26);
        var closed = session(date.atTime(9, 0), date.atTime(17, 0));
        var open = session(date.atTime(10, 0), null);
        open.setLastEntryTime(date.atTime(17, 10));
        assertThat(service.inferDay(List.of(open, closed), date).currentlyInside()).isTrue();
        open.setManualOverride(true);
        assertThat(service.inferDay(List.of(open, closed), date).currentlyInside()).isFalse();
        open.setManualOverride(false); open.setLastEntryTime(date.atTime(17, 0));
        assertThat(service.inferDay(List.of(open, closed), date).currentlyInside()).isFalse();
    }

    @Test
    void overnightExitClosesOnlyItsWorkDateAndInvalidExitDoesNotCloseIt() {
        LocalDate date = LocalDate.of(2026, 4, 26);
        var open = session(date.atTime(20, 0), null);
        var closed = session(date.atTime(21, 0), date.plusDays(1).atTime(2, 0));
        var nextDayOpen = session(date.plusDays(1).atTime(9, 0), null);
        assertThat(service.inferDay(List.of(open, closed, nextDayOpen), date).currentlyInside()).isFalse();
        assertThat(service.inferDay(List.of(open, closed, nextDayOpen), date.plusDays(1)).currentlyInside()).isTrue();
        closed.setCheckOutTime(date.atTime(19, 0));
        assertThat(service.inferDay(List.of(open, closed), date).currentlyInside()).isTrue();
    }

    @Test
    void combinedAreaBucketsAreNotDeduplicatedAgainstOtherAreasOrDirectionalVisits() {
        LocalDate date = LocalDate.of(2026, 10, 9);
        var a = session(date.atTime(9, 0), null); a.setCombinedAreaId(10L);
        var b = session(date.atTime(9, 0, 20), date.atTime(18, 0)); b.setCombinedAreaId(20L);
        var directional = session(date.atTime(9, 0, 30), date.atTime(18, 0));
        assertThat(service.dedupeSessions(List.of(a, b, directional))).hasSize(3);
    }

    private static AttendanceLog session(LocalDateTime in, LocalDateTime out) {
        AttendanceLog log = new AttendanceLog();
        log.setEmployeeId(1L);
        log.setCheckInTime(in);
        log.setCheckOutTime(out);
        return log;
    }
}
