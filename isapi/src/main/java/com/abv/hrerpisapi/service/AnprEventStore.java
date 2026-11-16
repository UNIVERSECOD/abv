package com.abv.hrerpisapi.service;

import com.abv.hrerpisapi.device.mapper.AnprEventMapper;
import com.abv.hrerpisapi.device.client.AnprArchiveSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.stream.XMLStreamException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnprEventStore {
    private final JdbcTemplate jdbc;
    private final AnprEventMapper mapper;

    public record Capture(long id, OffsetDateTime capturedAt, String plate, Integer confidence, String channel,
                          boolean archived) {}

    @Transactional
    public boolean capture(long deviceId, String xml) throws XMLStreamException {
        var event = mapper.mapPayload(xml);
        if (event == null) return false;
        lockDevice(deviceId);
        String key = eventKey(event);
        var existingLive = jdbc.queryForList("""
                SELECT id FROM anpr_events WHERE device_id = ? AND (event_key = ? OR live_event_key = ?)
                """, Long.class, deviceId, key, key);
        if (!existingLive.isEmpty()) return true;

        var archive = matching(deviceId, event.capturedAt(), event.plate(), event.channel(), true);
        if (archive.size() == 1) {
            jdbc.update("""
                    UPDATE anpr_events SET live_event_key = ?, live_raw_xml = ?, confidence = ?
                    WHERE id = ? AND device_id = ?
                    """, key, xml, event.confidence(), archive.get(0), deviceId);
        } else {
            jdbc.update("""
                    INSERT INTO anpr_events (device_id, event_key, captured_at, plate, confidence, channel, raw_xml)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (device_id, event_key) DO NOTHING
                    """, deviceId, key, event.capturedAt(), event.plate(), event.confidence(), event.channel(), xml);
        }
        return true;
    }

    public List<Capture> read(long deviceId, OffsetDateTime start, OffsetDateTime end, long afterId, int limit) {
        return jdbc.query("""
                SELECT id, captured_at, plate, confidence, channel, archived FROM anpr_events
                WHERE device_id = ? AND id > ? AND captured_at >= ? AND captured_at <= ?
                ORDER BY id LIMIT ?
                """, (row, index) -> new Capture(row.getLong("id"),
                        row.getObject("captured_at", OffsetDateTime.class), row.getString("plate"),
                        row.getObject("confidence", Integer.class), row.getString("channel"), row.getBoolean("archived")),
                deviceId, afterId, start, end, limit);
    }

    @Transactional
    public int storeArchive(long deviceId, List<AnprArchiveSearch.Capture> captures) {
        if (captures.isEmpty()) return 0;
        lockDevice(deviceId);
        int inserted = 0;
        for (var capture : captures) {
            String plateKey = plateKey(capture.plate());
            String key = digest("archive\n" + capture.time().toInstant() + "\n" + plateKey);
            var live = matching(deviceId, capture.time(), capture.plate(), capture.channel(), false);
            if (live.size() == 1) {
                jdbc.update("""
                        UPDATE anpr_events SET archived = true, archive_raw_xml = COALESCE(archive_raw_xml, ?)
                        WHERE id = ? AND device_id = ? AND live_raw_xml IS NULL
                        """, capture.xml(), live.get(0), deviceId);
                continue;
            }
            inserted += jdbc.update("""
                    INSERT INTO anpr_events (device_id, event_key, captured_at, plate, channel, raw_xml, archived)
                    VALUES (?, ?, ?, ?, ?, ?, true)
                    ON CONFLICT (device_id, event_key) DO NOTHING
                    """, deviceId, key, capture.time(), capture.plate(), capture.channel(), capture.xml());
        }
        return inserted;
    }

    private List<Long> matching(long deviceId, OffsetDateTime time, String plate, String channel, boolean archiveOnly) {
        String normalized = plateKey(plate);
        if (normalized.isEmpty()) return List.of();
        return jdbc.queryForList("""
                SELECT id FROM anpr_events
                WHERE device_id = ? AND captured_at = ?
                  AND regexp_replace(upper(coalesce(plate, '')), '[[:space:]-]+', '', 'g') = ?
                  AND (channel IS NULL OR CAST(? AS TEXT) IS NULL OR channel = ?)
                  AND (? = (archived AND archive_raw_xml IS NULL AND live_event_key IS NULL))
                ORDER BY id LIMIT 2
                """, Long.class, deviceId, time, normalized, (Object) channel, channel, archiveOnly);
    }

    private void lockDevice(long deviceId) {
        jdbc.queryForObject("SELECT id FROM devices WHERE id = ? FOR NO KEY UPDATE", Long.class, deviceId);
    }

    private static String plateKey(String value) {
        return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s-]+", "");
    }

    private static String eventKey(AnprEventMapper.Event event) {
        // Firmware can reuse the common UUID for multiple captures of the same vehicle.
        String identity = String.join("\n", text(event.uuid()), event.capturedAt().toInstant().toString(),
                text(event.channel()), text(event.lane()), text(event.plate()));
        return digest(identity);
    }

    private static String digest(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
