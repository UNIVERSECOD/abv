package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO.AttendanceLogEntryDTO;
import com.hic.model.AttendanceLog;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogRepository;
import com.hic.util.AppTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CombinedDeviceAttendanceServiceTest {
    @Mock AttendanceLogRepository logs;
    @Mock EmployeeShiftResolver shifts;
    CombinedDeviceAttendanceService service;
    Employee employee;
    List<AttendanceLog> stored;
    final LocalDate day = LocalDate.of(2026, 10, 9);

    @BeforeEach void setup() {
        employee = new Employee(); employee.setId(5L); employee.setTenantId(1L);
        stored = new ArrayList<>();
        service = new CombinedDeviceAttendanceService(logs, shifts);
        lenient().when(shifts.resolve(eq(employee), any())).thenReturn(
                new EmployeeShiftResolver.ResolvedShift(8L, "STANDARD"));
        lenient().when(logs.findByTenantIdAndEmployeeIdAndCombinedAreaIdAndCombinedWorkDate(any(), any(), any(), any()))
                .thenAnswer(call -> stored.stream().filter(s -> s.getTenantId().equals(call.getArgument(0))
                        && s.getEmployeeId().equals(call.getArgument(1)) && s.getCombinedAreaId().equals(call.getArgument(2))
                        && s.getCombinedWorkDate().equals(call.getArgument(3))).findFirst());
        lenient().when(logs.save(any())).thenAnswer(call -> {
            AttendanceLog s = call.getArgument(0);
            if (s.getId() == null) { s.setId((long) stored.size() + 1); stored.add(s); }
            return s;
        });
    }

    @Test void oneScanRemainsOpenAndReplayingItCannotBecomeAnExit() {
        var first = punch(1, 101, 9, 0);
        assertThat(service.accept(1L, employee, 10L, first).changed()).isTrue();
        assertThat(service.accept(1L, employee, 10L, first).changed()).isFalse();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCheckOutTime()).isNull();
        assertThat(stored.get(0).getStatus()).isEqualTo("MISSING_EXIT");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void scanOrderingAndReplayKeepSameCalendarDayExtrema(int seed) {
        var punches = new ArrayList<AttendanceLogEntryDTO>();
        punches.add(punch(1, 101, 8, 0)); punches.add(punch(2, 102, 12, 0));
        punches.add(punch(3, 103, 18, 0)); punches.add(punch(4, 104, 8, 0));
        java.util.Collections.shuffle(punches, new java.util.Random(seed));
        punches.forEach(p -> service.accept(1L, employee, 10L, p));
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getCheckInTime()).isEqualTo(day.atTime(8, 0));
        assertThat(stored.get(0).getCheckOutTime()).isEqualTo(day.atTime(18, 0));
        punches.forEach(p -> assertThat(service.accept(1L, employee, 10L, p).changed()).isFalse());
    }

    @Test void sixDevicesInSameAreaKeepFirstEntryAndContinuouslyExtendLastExit() {
        for (int i = 0; i < 6; i++) service.accept(1L, employee, 10L, punch(i + 1, 101 + i, 9 + i, 0));
        assertThat(stored).hasSize(1);
        var s = stored.get(0);
        assertThat(s.getCheckInTime()).isEqualTo(day.atTime(9, 0));
        assertThat(s.getCheckOutTime()).isEqualTo(day.atTime(14, 0));
        assertThat(s.getDoorId()).isEqualTo("101:106");
        assertThat(s.getEntryPunchId()).isEqualTo(1);
        assertThat(s.getExitPunchId()).isEqualTo(6);
        assertThat(s.getStatus()).isEqualTo("CLOSED");
        service.accept(1L, employee, 10L, punch(7, 104, 23, 59));
        assertThat(s.getCheckOutTime()).isEqualTo(day.atTime(23, 59));
        for (int i = 0; i < 6; i++) assertThat(service.accept(1L, employee, 10L,
                punch(i + 1, 101 + i, 9 + i, 0)).changed()).isFalse();
        assertThat(s.getCheckOutTime()).isEqualTo(day.atTime(23, 59));
    }

    @Test void delayedEarlierScanMovesEntryAndPreservesFormerEntryAsExit() {
        service.accept(1L, employee, 10L, punch(2, 102, 18, 0));
        service.accept(1L, employee, 10L, punch(1, 101, 9, 0));
        var s = stored.get(0);
        assertThat(s.getCheckInTime()).isEqualTo(day.atTime(9, 0));
        assertThat(s.getCheckOutTime()).isEqualTo(day.atTime(18, 0));
        assertThat(s.getDoorId()).isEqualTo("101:102");
        service.accept(1L, employee, 10L, punch(3, 103, 8, 0));
        assertThat(s.getCheckInTime()).isEqualTo(day.atTime(8, 0));
        assertThat(s.getCheckOutTime()).isEqualTo(day.atTime(18, 0));
        assertThat(s.getDoorId()).isEqualTo("103:102");
    }

    @Test void separateAreasNeverPairWithEachOther() {
        service.accept(1L, employee, 10L, punch(1, 101, 9, 0));
        service.accept(1L, employee, 20L, punch(2, 102, 18, 0));
        assertThat(stored).hasSize(2).allMatch(s -> s.getCheckOutTime() == null);
    }

    @Test void midnightStartsNewDayUsingBakuTimeEvenWhenReceivedInUtc() {
        service.accept(1L, employee, 10L, punch(1, 101, 20, 0));
        service.accept(1L, employee, 10L, punch(2, 102, 23, 59));
        var midnight = punch(3, 103, 0, 0);
        midnight.setPunchTime(OffsetDateTime.parse("2026-10-09T20:00:00Z"));
        service.accept(1L, employee, 10L, midnight);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getCheckOutTime()).isEqualTo(day.atTime(23, 59));
        assertThat(stored.get(1).getCombinedWorkDate()).isEqualTo(day.plusDays(1));
        assertThat(stored.get(1).getCheckOutTime()).isNull();
    }

    @Test void flexibleAndUnknownSchedulesAreSkippedWithoutChangingRawSource() {
        for (String type : new String[]{"FLEXIBLE", "SERBEST", "FIRST_ENTRY", "FREE", "UNKNOWN", ""}) {
            when(shifts.resolve(employee, day)).thenReturn(new EmployeeShiftResolver.ResolvedShift(8L, type));
            assertThat(service.accept(1L, employee, 10L, punch(1, 101, 9, 0)).skipped()).isTrue();
        }
        verify(logs, never()).save(any());
    }

    @Test void manualCorrectionIsNeverOverwrittenByReplayOrLaterScan() {
        service.accept(1L, employee, 10L, punch(1, 101, 9, 0));
        var s = stored.get(0); s.setManualOverride(true); s.setCheckOutTime(day.atTime(17, 0));
        assertThat(service.accept(1L, employee, 10L, punch(2, 102, 19, 0)).skipped()).isTrue();
        assertThat(s.getCheckOutTime()).isEqualTo(day.atTime(17, 0));
    }

    @Test void rejectsForeignTenantAndMissingAreaBeforeReadingOrWriting() {
        assertThat(service.accept(2L, employee, 10L, punch(1, 101, 9, 0)).skipped()).isTrue();
        assertThat(service.accept(1L, employee, null, punch(1, 101, 9, 0)).skipped()).isTrue();
        verifyNoInteractions(logs, shifts);
    }

    @Test void frozenScheduleSurvivesSubsequentScheduleChange() {
        service.accept(1L, employee, 10L, punch(1, 101, 9, 0));
        lenient().when(shifts.resolve(employee, day)).thenReturn(new EmployeeShiftResolver.ResolvedShift(9L, "FLEXIBLE"));
        service.accept(1L, employee, 10L, punch(2, 102, 18, 0));
        assertThat(stored.get(0).getTimetableId()).isEqualTo(8);
        assertThat(stored.get(0).getShiftType()).isEqualTo("STANDARD");
        verify(shifts, times(1)).resolve(employee, day);
    }

    @Test void punchAlreadyUsedByDirectionalSessionIsNotReimported() {
        when(logs.findByTenantIdAndEntryPunchId(1L, 1L)).thenReturn(Optional.of(new AttendanceLog()));
        assertThat(service.accept(1L, employee, 10L, punch(1, 101, 9, 0)).skipped()).isTrue();
        verify(logs, never()).save(any());
    }

    private AttendanceLogEntryDTO punch(long id, long device, int hour, int minute) {
        var p = new AttendanceLogEntryDTO(); p.setId(id); p.setDeviceId(device);
        p.setEmployeeNo("TEST-5"); p.setPunchTime(day.atTime(hour, minute).atZone(AppTimeZone.ZONE).toOffsetDateTime());
        return p;
    }
}
