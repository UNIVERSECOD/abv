package com.hic.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AnprArchiveAutoSyncTest {
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Test
    void initialBackfillStartsAtOctoberAndAdvancesOneDayAtATime() {
        var first = AnprArchiveAutoSync.window(OCTOBER, OCTOBER, TODAY);
        assertThat(first.from()).isEqualTo(OCTOBER);
        assertThat(first.to()).isEqualTo(OCTOBER);
        var next = AnprArchiveAutoSync.window(OCTOBER, first.next(), TODAY);
        assertThat(next.from()).isEqualTo(first.to());
        assertThat(next.to()).isEqualTo(OCTOBER.plusDays(1));
    }

    @Test
    void recentRecoveryRevisitsYesterdayAndDoesNotMarkTodayComplete() {
        var result = AnprArchiveAutoSync.window(OCTOBER, TODAY, TODAY);
        assertThat(result.from()).isEqualTo(TODAY.minusDays(1));
        assertThat(result.to()).isEqualTo(TODAY);
        assertThat(result.next()).isEqualTo(TODAY);
    }
}
