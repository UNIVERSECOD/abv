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

    public boolean capture(long deviceId, String xml) throws XMLStreamException {
        var event = mapper.map(xml);
        if (event == null) return false;
        jdbc.update("""
                INSERT INTO anpr_events (device_id, event_key, captured_at, plate, confidence, channel, raw_xml)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (device_id, event_key) DO NOTHING
                """, deviceId, eventKey(event), event.capturedAt(), event.plate(), event.confidence(), event.channel(), xml);
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
        int inserted = 0;
        for (var capture : captures) {
            String plateKey = capture.plate() == null ? ""
                    : capture.plate().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            String key = digest("archive\n" + capture.time().toInstant() + "\n" + plateKey);
            inserted += jdbc.update("""
                    INSERT INTO anpr_events (device_id, event_key, captured_at, plate, channel, raw_xml, archived)
                    SELECT ?, ?, ?, ?, ?, ?, true
                    WHERE NOT EXISTS (
                        SELECT 1 FROM anpr_events WHERE device_id = ? AND captured_at = ?
                            AND regexp_replace(upper(coalesce(plate, '')), '[^A-Z0-9]', '', 'g') = ?
                    )
                    ON CONFLICT (device_id, event_key) DO NOTHING
                    """, deviceId, key, capture.time(), capture.plate(), capture.channel(), capture.xml(),
                    deviceId, capture.time(), plateKey);
        }
        return inserted;
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
