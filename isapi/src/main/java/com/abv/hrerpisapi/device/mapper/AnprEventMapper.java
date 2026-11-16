package com.abv.hrerpisapi.device.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AnprEventMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENVELOPE_FIELDS = Set.of("eventType", "eventState", "dateTime", "channelID", "UUID");
    private static final Set<String> ANPR_FIELDS = Set.of("licensePlate", "confidenceLevel", "UUID", "line");

    public record Event(OffsetDateTime capturedAt, String plate, Integer confidence, String channel,
                        String uuid, String lane) {}

    public Event mapPayload(String payload) throws XMLStreamException {
        if (payload == null || payload.isBlank()) return null;
        String trimmed = payload.stripLeading();
        if (trimmed.startsWith("{")) return mapJson(trimmed);
        return map(payload);
    }

    /** Returns field names only; values (including plates) are never included. */
    public String describePayload(String payload) {
        if (payload == null || payload.isBlank()) return "empty";
        String trimmed = payload.stripLeading();
        try {
            if (trimmed.startsWith("{")) {
                JsonNode root = JSON.readTree(trimmed);
                JsonNode envelope = root.path("EventNotificationAlert");
                if (!envelope.isObject()) envelope = root;
                return "json rootFields=" + fieldNames(root) + " envelopeFields=" + fieldNames(envelope)
                        + " eventType=" + safeName(text(envelope, "eventType"));
            }
            if (trimmed.startsWith("<")) {
                XMLInputFactory factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                var reader = factory.createXMLStreamReader(new StringReader(trimmed));
                var names = new java.util.LinkedHashSet<String>();
                String root = "-";
                String eventType = "-";
                try {
                    while (reader.hasNext() && names.size() < 24) {
                        int token = reader.next();
                        if (token != XMLStreamConstants.START_ELEMENT) continue;
                        String name = safeName(reader.getLocalName());
                        if ("-".equals(root)) root = name;
                        names.add(name);
                        if ("eventType".equals(name)) eventType = safeName(reader.getElementText().trim());
                    }
                } finally {
                    reader.close();
                }
                return "xml root=" + root + " fields=" + names + " eventType=" + eventType;
            }
            return "other";
        } catch (Exception ignored) {
            return trimmed.startsWith("{") ? "invalid-json" : trimmed.startsWith("<") ? "invalid-xml" : "other";
        }
    }

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

    private Event mapJson(String payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            JsonNode envelope = root.path("EventNotificationAlert");
            if (!envelope.isObject()) envelope = root;
            if (!"ANPR".equalsIgnoreCase(text(envelope, "eventType"))
                    || "inactive".equalsIgnoreCase(text(envelope, "eventState"))) return null;
            String capturedAt = text(envelope, "dateTime");
            if (capturedAt == null) return null;
            JsonNode anpr = envelope.path("ANPR");
            return new Event(OffsetDateTime.parse(capturedAt), bounded(text(anpr, "licensePlate"), 64),
                    confidence(text(anpr, "confidenceLevel")), bounded(text(envelope, "channelID"), 32),
                    bounded(first(text(anpr, "UUID"), text(envelope, "UUID")), 128),
                    bounded(text(anpr, "line"), 32));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static String first(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        if (node != null && node.isObject()) node.fieldNames().forEachRemaining(name -> {
            if (names.size() < 24) names.add(safeName(name));
        });
        return names;
    }

    private static String safeName(String value) {
        return value == null || !value.matches("[A-Za-z0-9_.:-]{1,64}") ? "-" : value;
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
