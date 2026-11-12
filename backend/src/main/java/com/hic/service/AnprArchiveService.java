package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.model.DeviceConfig;
import com.hic.repository.DeviceConfigRepository;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AnprArchiveService {
    private final DeviceConfigRepository devices;
    private final AnprBridgeClient bridge;
    private final AnprAttendanceService attendance;
    private final AttendanceCalculationService calculations;
    private final AttendanceService summaries;
    private final TransactionTemplate transactions;

    private record Camera(long id, long bridgeId, String role, String endpoint, Integer port,
                          java.time.LocalDateTime archiveSafeSince) {}
    private record Loaded(Camera camera, int found, List<AnprBridgeClient.Capture> captures) {}
    public record Result(int entryFound, int exitFound, int eligibleDays, int recalculatedDays, int unchangedDays, int skipped) {}

    public Result readAndApply(long tenant, long area, LocalDate from, LocalDate to) {
        var now = AppTimeZone.nowOffset();
        if (from == null || to == null || to.isBefore(from) || to.isAfter(now.toLocalDate())
                || ChronoUnit.DAYS.between(from, to) > 60) {
            throw new BadRequestException("Arxiv üçün maksimum 61 günlük etibarlı tarix aralığı seçin.");
        }
        var cameras = cameras(tenant, area);
        OffsetDateTime start = from.atStartOfDay(AppTimeZone.ZONE).toOffsetDateTime();
        // The last selected workday can have an exit on the following calendar day.
        OffsetDateTime lastPossibleExit = to.plusDays(2).atStartOfDay(AppTimeZone.ZONE)
                .toOffsetDateTime().minusNanos(1000);
        OffsetDateTime end = lastPossibleExit.isBefore(now) ? lastPossibleExit : now;
        List<Loaded> loaded = new ArrayList<>();
        for (Camera camera : cameras) {
            try {
                var result = bridge.fetchArchive(camera.bridgeId(), start, end);
                loaded.add(new Loaded(camera, result.found(), bridge.captures(camera.bridgeId(), start, end)));
            } catch (RestClientException | IllegalStateException ex) {
                String label = "ENTRY".equals(camera.role()) ? "Giriş" : "Çıxış";
                throw new BadRequestException(label + " kamerasının arxivi tam oxunmadı. Davamiyyətə dəyişiklik tətbiq edilmədi.");
            }
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            attendance.lockTenant(tenant);
            if (!cameras.equals(cameras(tenant, area))) {
                throw new BadRequestException("Oxuma zamanı kamera təyinatları dəyişib. Arxiv sorğusunu təkrarlayın.");
            }
            var workDays = new LinkedHashSet<AnprAttendanceService.WorkDay>();
            int skipped = 0;
            for (Loaded source : loaded) {
                var device = devices.findById(source.camera().id()).filter(row -> Long.valueOf(tenant).equals(row.getTenantId()))
                        .orElseThrow(() -> new BadRequestException("ANPR kamera təyinatı tapılmadı."));
                skipped += attendance.persistCamera(tenant, device, source.camera().bridgeId(), List.of(),
                        source.captures(), from, to, workDays).skipped();
            }
            int recalculated = 0;
            for (var day : workDays.stream().sorted(Comparator.comparing(AnprAttendanceService.WorkDay::date)
                    .thenComparingLong(AnprAttendanceService.WorkDay::employee)).toList()) {
                if (attendance.projectArchive(tenant, day) == null) continue;
                calculations.calculateForDay(day.employee(), day.date());
                summaries.generateDailySummary(day.employee(), day.date());
                recalculated++;
            }
            return new Result(loaded.get(0).found(), loaded.get(1).found(), workDays.size(), recalculated,
                    workDays.size() - recalculated, skipped);
        }));
    }

    private List<Camera> cameras(long tenant, long area) {
        var cameras = devices.findByTenantIdAndBranchId(tenant, area).stream()
                .filter(device -> device.isAnpr() && "ACTIVE".equalsIgnoreCase(device.getStatus()))
                .sorted(Comparator.comparing(DeviceConfig::getDoorRole, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(device -> new Camera(device.getId(), bridgeId(device), device.getDoorRole(),
                        device.getDeviceIp(), device.getDevicePort(), device.getAnprArchiveSafeSince())).toList();
        if (cameras.size() != 2 || !"ENTRY".equals(cameras.get(0).role()) || !"EXIT".equals(cameras.get(1).role())
                || cameras.get(0).bridgeId() == cameras.get(1).bridgeId()) {
            throw new BadRequestException("Arxiv oxumaq üçün eyni ərazidə bir giriş və bir çıxış ANPR kamerası seçilməlidir.");
        }
        return cameras;
    }

    private static long bridgeId(DeviceConfig device) {
        try {
            long id = Long.parseLong(device.getDeviceId());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Report a local mapping problem before contacting the bridge.
        }
        throw new BadRequestException("ANPR kameranın bağlantı identifikatoru etibarlı deyil.");
    }
}
