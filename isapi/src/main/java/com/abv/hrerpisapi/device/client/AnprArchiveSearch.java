package com.abv.hrerpisapi.device.client;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Hikvision ContentMgmt search for the single-channel camera's vehicle pictures. */
public final class AnprArchiveSearch {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 2000;
    private static final int MAX_RESPONSE_CHARS = 8 * 1024 * 1024;

    private AnprArchiveSearch() {}

    public record Capture(OffsetDateTime time, String plate, String channel, String xml) {}
    record Page(List<Capture> captures, boolean more, int records) {}
    static final class UnsupportedSearch extends IOException {
        UnsupportedSearch() { super("Camera does not support this archive search"); }
    }

    @FunctionalInterface
    interface SearchRequest {
        String search(String request) throws IOException, InterruptedException;
    }

    static List<Capture> read(OffsetDateTime start, OffsetDateTime end, SearchRequest request)
            throws IOException, InterruptedException {
        return read(start, end, request, false);
    }

    static List<Capture> read(OffsetDateTime start, OffsetDateTime end, SearchRequest request, boolean vehicleSearch)
            throws IOException, InterruptedException {
        var result = new ArrayList<Capture>();
        readPages(start, end, request, vehicleSearch, result::addAll);
        return result;
    }

    static int readPages(OffsetDateTime start, OffsetDateTime end, SearchRequest request, boolean vehicleSearch,
                         Consumer<List<Capture>> save) throws IOException, InterruptedException {
        var searchId = UUID.randomUUID();
        int offset = 0;
        int found = 0;
        List<Capture> previous = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Page parsed;
            try {
                parsed = parse(request.search(query(searchId, offset, start, end, vehicleSearch, PAGE_SIZE)), searchId);
            } catch (UnsupportedSearch unsupported) {
                if (page == 0) throw unsupported;
                throw new IOException("Camera stopped accepting ANPR archive continuation requests");
            }
            if (parsed.records() > 0 && parsed.captures().equals(previous)) {
                throw new IOException("Camera repeated an ANPR archive page");
            }
            previous = parsed.captures();
            var captures = parsed.captures().stream()
                    .filter(row -> !row.time().isBefore(start) && !row.time().isAfter(end)).toList();
            if (!captures.isEmpty()) save.accept(captures);
            found += captures.size();
            if (!parsed.more()) return found;
            if (parsed.records() == 0) throw new IOException("ANPR archive returned an empty continuation page");
            offset += parsed.records();
        }
        throw new IOException("ANPR archive returned too many pages; use a shorter date range");
    }

    static String query(UUID id, int offset, OffsetDateTime start, OffsetDateTime end, boolean vehicleSearch, int pageSize) {
        if (vehicleSearch) {
            return """
                    <VehicleRecognitionSearchDescription version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
                      <searchID>%s</searchID><searchResultPosition>%d</searchResultPosition><maxResults>%d</maxResults>
                      <timeSpanList><timeSpan><startTime>%s</startTime><endTime>%s</endTime></timeSpan></timeSpanList>
                    </VehicleRecognitionSearchDescription>
                    """.formatted(id, offset, pageSize, start.truncatedTo(ChronoUnit.SECONDS), end.truncatedTo(ChronoUnit.SECONDS));
        }
        return """
                <CMSearchDescription version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
                  <searchID>%s</searchID><trackIDList><trackID>103</trackID></trackIDList>
                  <timeSpanList><timeSpan><startTime>%s</startTime><endTime>%s</endTime></timeSpan></timeSpanList>
                  <contentTypeList><contentType>metadata</contentType></contentTypeList>
                  <maxResults>%d</maxResults><searchResultPostion>%d</searchResultPostion>
                  <metadataList><metadataDescriptor>recordType.meta..com/vehicleDetection</metadataDescriptor></metadataList>
                </CMSearchDescription>
                """.formatted(id, start.toInstant().truncatedTo(ChronoUnit.SECONDS),
                end.toInstant().truncatedTo(ChronoUnit.SECONDS), pageSize, offset);
    }

    static Page parse(String xml, UUID searchId) throws IOException {
        if (xml == null || xml.length() > MAX_RESPONSE_CHARS) throw new IOException("Invalid ANPR archive response size");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if ("ResponseStatus".equals(root.getLocalName())) {
                if ("notSupport".equalsIgnoreCase(value(root, "subStatusCode"))) throw new UnsupportedSearch();
                throw new IOException("Camera rejected the ANPR archive request");
            }
            boolean vehicleSearch = "VehicleRecognitionSearchResult".equals(root.getLocalName());
            if ((!vehicleSearch && !"CMSearchResult".equals(root.getLocalName()))
                    || !searchId.toString().equals(value(root, "searchID"))) {
                throw new IOException("Camera did not return the requested ANPR archive search");
            }
            String status = value(root, "responseStatusStrg");
            String succeeded = value(root, "responseStatus");
            if ((!succeeded.isEmpty() && !"true".equalsIgnoreCase(succeeded))
                    || !List.of("OK", "MORE", "NO MATCHES").contains(status)) {
                throw new IOException("Camera rejected the ANPR archive search");
            }
            var rows = root.getElementsByTagNameNS("*", vehicleSearch ? "VehicleMode" : "searchMatchItem");
            if (rows.getLength() > 500 || ("NO MATCHES".equals(status) && rows.getLength() > 0)) {
                throw new IOException("ANPR archive response has an invalid page size");
            }
            var result = new ArrayList<Capture>();
            var transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformerFactory.newTransformer();
            for (int i = 0; i < rows.getLength(); i++) {
                var item = (Element) rows.item(i);
                String time = value(item, vehicleSearch ? "captureTime" : "startTime");
                if (time.isEmpty()) throw new IOException("ANPR archive row has no timestamp");
                String plate = value(item, vehicleSearch ? "licensePlate" : "plateNo");
                if (plate.length() > 64) throw new IOException("ANPR archive row has an invalid plate field");
                result.add(new Capture(OffsetDateTime.parse(time), plate.isBlank() ? null : plate, "1",
                        serialize(item, transformer)));
            }
            return new Page(result, "MORE".equals(status), rows.getLength());
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("ANPR archive response could not be parsed");
        }
    }

    private static String value(Element parent, String name) {
        var fields = parent.getElementsByTagNameNS("*", name);
        return fields.getLength() == 0 ? "" : fields.item(0).getTextContent().trim();
    }

    private static String serialize(Element item, Transformer transformer) throws Exception {
        var writer = new StringWriter();
        transformer.transform(new DOMSource(item), new StreamResult(writer));
        return writer.toString();
    }
}
