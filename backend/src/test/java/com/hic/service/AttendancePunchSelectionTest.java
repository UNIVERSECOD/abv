package com.hic.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.hic.service.AttendancePunchSelection.Direction.ENTRY;
import static com.hic.service.AttendancePunchSelection.Direction.EXIT;
import static com.hic.service.AttendancePunchSelection.Method.ANPR;
import static com.hic.service.AttendancePunchSelection.Method.FACE;
import static org.assertj.core.api.Assertions.assertThat;

class AttendancePunchSelectionTest {
    @Test
    void daytimeUsesFirstEntryAndLastExitAcrossMethods() {
        var first = punch(1, "2026-11-10T08:50", FACE, ENTRY);
        var last = punch(4, "2026-11-10T18:12", ANPR, EXIT);
        var selected = AttendancePunchSelection.select(List.of(last,
                punch(3, "2026-11-10T18:00", FACE, EXIT),
                punch(2, "2026-11-10T08:55", ANPR, ENTRY), first), false).orElseThrow();
        assertThat(selected.entry()).isEqualTo(first);
        assertThat(selected.exit()).isEqualTo(last);
    }

    @Test
    void overnightUsesLatestExitOfEntryMethodAfterMidnight() {
        var first = punch(1, "2026-11-10T20:55", ANPR, ENTRY);
        var lastMatching = punch(4, "2026-11-11T06:17", ANPR, EXIT);
        var selected = AttendancePunchSelection.select(List.of(first,
                punch(2, "2026-11-10T21:00", FACE, ENTRY),
                punch(3, "2026-11-11T06:00", ANPR, EXIT), lastMatching,
                punch(5, "2026-11-11T06:20", FACE, EXIT)), true).orElseThrow();
        assertThat(selected.entry()).isEqualTo(first);
        assertThat(selected.exit()).isEqualTo(lastMatching);
    }

    @Test
    void overnightDoesNotCloseFaceEntryWithAnprExit() {
        var faceEntry = punch(1, "2026-11-10T20:55", FACE, ENTRY);
        var anprExit = punch(2, "2026-11-11T06:17", ANPR, EXIT);
        assertThat(AttendancePunchSelection.select(List.of(faceEntry, anprExit), true).orElseThrow().exit()).isNull();
        assertThat(AttendancePunchSelection.select(List.of(anprExit), true)).isEmpty();
    }

    @Test
    void eitherMethodCanCompleteASessionAlone() {
        for (var method : List.of(FACE, ANPR)) {
            var first = punch(1, "2026-11-10T20:55", method, ENTRY);
            var last = punch(2, "2026-11-11T06:17", method, EXIT);
            assertThat(AttendancePunchSelection.select(List.of(first, last), true).orElseThrow().exit()).isEqualTo(last);
        }
    }

    private static AttendancePunchSelection.Punch punch(long id, String time,
            AttendancePunchSelection.Method method, AttendancePunchSelection.Direction direction) {
        return new AttendancePunchSelection.Punch(id, id, LocalDateTime.parse(time), method, direction);
    }
}
