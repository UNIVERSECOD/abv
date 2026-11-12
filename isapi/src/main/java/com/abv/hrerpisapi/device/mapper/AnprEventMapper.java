package com.abv.hrerpisapi.device.mapper;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class AnprEventMapper {
    private static final Set<String> ENVELOPE_FIELDS = Set.of("eventType", "eventState", "dateTime", "channelID", "UUID");
    private static final Set<String> ANPR_FIELDS = Set.of("licensePlate", "confidenceLevel", "UUID", "line");

    public record Event(OffsetDateTime capturedAt, String plate, Integer confidence, String channel,
                        String uuid, String lane) {}

    public Event map(String payload) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var reader = factory.createXMLStreamReader(new StringReader(payload));
        var path = new ArrayDeque<String>();
        Map<String, String> fields = new HashMap<>();
        try {
            while (reader.hasNext()) {
                int token = reader.next();
                if (token == XMLStreamConstants.DTD) throw new XMLStreamException("Unexpected DTD");
                if (token == XMLStreamConstants.END_ELEMENT) path.removeLast();
                if (token != XMLStreamConstants.START_ELEMENT) continue;
                String name = reader.getLocalName();
                if (path.isEmpty() && !"EventNotificationAlert".equals(name)) return null;
                if ((path.size() == 1 && ENVELOPE_FIELDS.contains(name))
                        || (path.size() == 2 && "ANPR".equals(path.peekLast()) && ANPR_FIELDS.contains(name))) {
                    String value = reader.getElementText().trim();
                    if (path.size() == 1) fields.put(name, value);
                    else fields.putIfAbsent(name, value);
                } else {
                    path.addLast(name);
                }
            }
        } finally {
            reader.close();
        }
        if (!"ANPR".equalsIgnoreCase(fields.get("eventType"))
                || "inactive".equalsIgnoreCase(fields.get("eventState"))) return null;
        String capturedAt = fields.get("dateTime");
        if (capturedAt == null) return null;
        try {
            return new Event(OffsetDateTime.parse(capturedAt), bounded(fields.get("licensePlate"), 64),
                    confidence(fields.get("confidenceLevel")), bounded(fields.get("channelID"), 32),
                    bounded(fields.get("UUID"), 128), bounded(fields.get("line"), 32));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String bounded(String value, int length) {
        return value == null || value.isBlank() || value.length() > length ? null : value;
    }

    private static Integer confidence(String value) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && parsed <= 100 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
