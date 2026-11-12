package com.hic.service;

import com.hic.model.DeviceConfig;
import com.hic.model.Tenant;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.TenantRepository;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnprArchiveAutoSync {
    private static final int RETRY_MINUTES = 15;
    private static final int MIN_RECOVERY_INTERVAL_MINUTES = 15;
    private static final int LEASE_MINUTES = 30;
    private final TenantRepository tenants;
    private final DeviceConfigRepository devices;
    private final JdbcTemplate jdbc;
    private final AnprArchiveJobs jobs;

    @Value("${attendance.anpr.archive-start:2026-10-01}")
    private LocalDate firstDay;

    private record Pair(long tenant, long area, long entry, long exit) {}
    record Window(LocalDate from, LocalDate to, LocalDate next) {}

    @Scheduled(fixedDelayString = "${attendance.anpr.scheduler-check-ms:60000}")
    public void schedule() {
        OffsetDateTime now = AppTimeZone.nowOffset();
        if (now.toLocalDate().isBefore(firstDay)) return;
        for (Tenant tenant : tenants.findAll()) {
            var grouped = devices.findByTenantIdAndStatus(tenant.getId(), "ACTIVE").stream()
                    .filter(device -> device.isAnpr() && device.getBranchId() != null)
                    .collect(Collectors.groupingBy(DeviceConfig::getBranchId));
            grouped.forEach((area, cameras) -> {
                Pair pair = pair(tenant.getId(), area, cameras);
                if (pair == null) return;
                try {
                    queue(pair, now, interval(tenant));
                } catch (RuntimeException ex) {
                    log.warn("Could not queue ANPR archive sync for tenant {} area {}: {}",
                            pair.tenant(), pair.area(), ex.getClass().getSimpleName());
                }
            });
        }
    }

    private void queue(Pair pair, OffsetDateTime now, int interval) {
        UUID lease = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO anpr_archive_sync_progress
                    (tenant_id, area_id, entry_bridge_id, exit_bridge_id, next_day, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, pair.tenant(), pair.area(), pair.entry(), pair.exit(), firstDay, now);
        var claimed = jdbc.query("""
                UPDATE anpr_archive_sync_progress SET lease_id = ?, next_attempt_at = ?
                WHERE tenant_id = ? AND area_id = ? AND entry_bridge_id = ? AND exit_bridge_id = ?
                    AND next_attempt_at <= ?
                RETURNING next_day
                """, (row, index) -> row.getObject(1, LocalDate.class), lease, now.plusMinutes(LEASE_MINUTES),
                pair.tenant(), pair.area(), pair.entry(), pair.exit(), now);
        if (claimed.isEmpty()) return;
        LocalDate today = now.toLocalDate();
        Window window = window(firstDay, claimed.get(0), today);
        if (!jobs.startAutomatic(pair.tenant(), pair.area(), window.from(), window.to(), success ->
                finish(pair, lease, window, today, interval, success))) {
            finish(pair, lease, window, today, 1, false, 1);
        }
    }

    private void finish(Pair pair, UUID lease, Window window, LocalDate today, int interval, boolean success) {
        finish(pair, lease, window, today, interval, success, RETRY_MINUTES);
    }

    private void finish(Pair pair, UUID lease, Window window, LocalDate today, int interval, boolean success, int retry) {
        int delay = success ? (window.to().isBefore(today) ? 1 : interval) : retry;
        jdbc.update("""
                UPDATE anpr_archive_sync_progress
                SET lease_id = NULL, next_attempt_at = ?, next_day = CASE WHEN ? THEN ? ELSE next_day END
                WHERE tenant_id = ? AND area_id = ? AND entry_bridge_id = ? AND exit_bridge_id = ? AND lease_id = ?
                """, AppTimeZone.nowOffset().plusMinutes(delay), success, window.next(),
                pair.tenant(), pair.area(), pair.entry(), pair.exit(), lease);
    }

    static Window window(LocalDate firstDay, LocalDate nextDay, LocalDate today) {
        LocalDate pending = nextDay.isBefore(firstDay) ? firstDay : nextDay.isAfter(today) ? today : nextDay;
        LocalDate from = pending.isAfter(firstDay) ? pending.minusDays(1) : firstDay;
        return new Window(from, pending, pending.isBefore(today) ? pending.plusDays(1) : today);
    }

    private static Pair pair(long tenant, long area, List<DeviceConfig> cameras) {
        if (cameras.size() != 2) return null;
        var entry = cameras.stream().filter(device -> "ENTRY".equals(device.getDoorRole())).findFirst();
        var exit = cameras.stream().filter(device -> "EXIT".equals(device.getDoorRole())).findFirst();
        if (entry.isEmpty() || exit.isEmpty()) return null;
        try {
            long entryId = Long.parseLong(entry.get().getDeviceId());
            long exitId = Long.parseLong(exit.get().getDeviceId());
            return entryId > 0 && exitId > 0 && entryId != exitId ? new Pair(tenant, area, entryId, exitId) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int interval(Tenant tenant) {
        Integer configured = tenant.getAttendanceSyncIntervalMinutes();
        return Math.max(MIN_RECOVERY_INTERVAL_MINUTES,
                configured != null && configured > 0 ? configured : Tenant.DEFAULT_ATTENDANCE_SYNC_INTERVAL_MINUTES);
    }
}
