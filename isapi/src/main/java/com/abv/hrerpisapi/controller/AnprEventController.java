package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.service.AnprEventStore;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.client.IsapiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class AnprEventController {
    private final AnprEventStore events;
    private final DeviceRepository devices;
    private final IsapiClient client;
    private final java.util.Set<Long> activeArchives = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public record ArchiveResult(int found, int added) {}

    @PostMapping("/api/anpr/archive")
    public ArchiveResult archive(@RequestHeader("X-Allowed-Device-Ids") String scope, @RequestParam long deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        requireScope(scope, deviceId);
        if (end.isBefore(start) || Duration.between(start, end).compareTo(Duration.ofDays(62)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var camera = devices.findById(deviceId).filter(device -> device.isAnpr())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!activeArchives.add(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Camera archive search is already running");
        }
        try {
            var added = new java.util.concurrent.atomic.AtomicInteger();
            int found = client.searchAnprArchive(camera, start, end,
                    page -> added.addAndGet(events.storeArchive(deviceId, page)));
            return new ArchiveResult(found, added.get());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ANPR archive read was interrupted");
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } finally {
            activeArchives.remove(deviceId);
        }
    }

    @GetMapping("/api/anpr/events")
    public List<AnprEventStore.Capture> read(
            @RequestHeader("X-Allowed-Device-Ids") String scope,
            @RequestParam long deviceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "500") int limit) {
        requireScope(scope, deviceId);
        if (deviceId <= 0 || afterId < 0 || limit < 1 || limit > 500 || end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return events.read(deviceId, start, end, afterId, limit);
    }

    private static void requireScope(String scope, long deviceId) {
        boolean allowed = Arrays.stream(scope.split(",")).map(String::trim)
                .anyMatch(Long.toString(deviceId)::equals);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
