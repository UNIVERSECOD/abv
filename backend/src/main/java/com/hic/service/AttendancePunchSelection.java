package com.hic.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/** Chooses the attendance endpoints from the punches already assigned to one work window. */
public final class AttendancePunchSelection {
    public enum Method { FACE, ANPR, CARD, ACCESS }

    public enum Direction { ENTRY, EXIT }

    public record Punch(long id, long deviceId, LocalDateTime time, Method method, Direction direction) {}

    public record Selection(Punch entry, Punch exit) {}

    private static final Comparator<Punch> BY_TIME = Comparator.comparing(Punch::time)
            .thenComparing(Punch::method)
            .thenComparingLong(Punch::deviceId)
            .thenComparingLong(Punch::id);

    private AttendancePunchSelection() {}

    public static Optional<Selection> select(Collection<Punch> punches, boolean overnight) {
        return punches.stream()
                .filter(punch -> punch.direction() == Direction.ENTRY)
                .min(BY_TIME)
                .map(entry -> new Selection(entry, latestExit(punches, entry, overnight)));
    }

    private static Punch latestExit(Collection<Punch> punches, Punch entry, boolean overnight) {
        return punches.stream()
                .filter(punch -> punch.direction() == Direction.EXIT)
                .filter(punch -> punch.time().isAfter(entry.time()))
                .filter(punch -> !overnight || punch.method() == entry.method())
                .max(BY_TIME)
                .orElse(null);
    }
}
