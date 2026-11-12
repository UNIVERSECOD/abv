package com.abv.hrerpisapi.device.client;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.device.DeviceTargetPolicy;
import com.abv.hrerpisapi.device.DigestHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Read-only diagnostics. Never writes a trial plate or changes camera configuration. */
@Service
@RequiredArgsConstructor
public class AnprCapabilities {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DeviceTargetPolicy targets;

    public record Feature(String status, String reason) {}
    public record Report(Instant checkedAt, String model, String firmware,
                         Feature plateWrite, Feature ownerName, Feature archiveRead) {}
    private record PlateFeatures(Feature write, Feature name) {}

    public Report check(DeviceEntity device) {
        targets.requireAllowed(device.getIp());
        return inspect(new DigestHttpClient("http://" + device.getIp(), device.getUsername(), device.getPassword(), true, Duration.ofSeconds(5)));
    }

    static Report inspect(DigestHttpClient client) {
        String model;
        String firmware;
        try {
            var info = xml(body(client.get("/ISAPI/System/deviceInfo")), "DeviceInfo");
            model = field(info, "model");
            firmware = field(info, "firmwareVersion");
        } catch (IOException | InterruptedException ex) {
            var failure = failure(ex);
            return new Report(Instant.now(), "", "", failure, failure, failure);
        }
        PlateFeatures plate;
        try {
            plate = plate(client);
        } catch (IOException | InterruptedException ex) {
            var failure = failure(ex);
            plate = new PlateFeatures(failure, failure);
        }
        Feature archive;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            archive = archive(client, false);
        } catch (IOException | InterruptedException ex) {
            archive = failure(ex);
        }
        return new Report(Instant.now(), model, firmware, plate.write(), plate.name(), archive);
    }

    private static PlateFeatures plate(DigestHttpClient client) throws IOException, InterruptedException {
        var payload = body(client.get(DigestHttpClient.ANPR_PLATE_CAPABILITIES));
        // Some firmware returns an XML ResponseStatus even for a JSON endpoint.
        if (payload.stripLeading().startsWith("<")) xml(payload, "LicensePlateInfoCap");
        JsonNode root = JSON.readTree(payload);
        if (root != null && "notSupport".equalsIgnoreCase(root.path("subStatusCode").asText())) throw new Unsupported();
        if (root == null || !root.path("LicensePlateInfoCap").isObject()) throw new IOException();
        var cap = root.path("LicensePlateInfoCap");
        Feature name = cap.path("name").path("@max").asInt(0) > 0
                ? unknown("Kamera ad sahəsini bildirir. Ad-soyadın yazılması sınaq yazısı edilmədən təsdiqlənmir.")
                : unsupported("Kamera ad-soyad sahəsini imkanlar cavabında bildirmir.");
        if (cap.path("id").path("@max").asInt(0) <= 0
                || cap.path("licensePlate").path("@max").asInt(0) <= 0) {
            return new PlateFeatures(unknown("Nömrə və qeyd identifikatorunun hədləri kameradan alınmadı."), name);
        }
        for (String operation : List.of("add", "modify")) {
            if (!options(cap, "operationType", operation)) {
                return new PlateFeatures(unsupported(operation.equals("add")
                        ? "Kamera ayrıca nömrə əlavə etmə imkanını bildirmir."
                        : "Kamera mövcud nömrəni ayrıca redaktə etmə imkanını bildirmir."), name);
            }
        }
        if (!options(cap, "listType", "allowList") || !options(cap, "plateColor", "unknown")
                || !options(cap, "plateType", "unknown")) {
            return new PlateFeatures(unsupported("Kamera Attendra-nın yeni nömrə üçün istifadə etdiyi icazə siyahısı, nömrə rəngi və ya növünü dəstəkləmir."), name);
        }
        try {
            UUID id = UUID.randomUUID();
            var result = xml(body(client.post(DigestHttpClient.ANPR_PLATE_SEARCH, "application/xml; charset=UTF-8", """
                    <LPListAuditSearchDescription xmlns="http://www.isapi.org/ver20/XMLSchema" version="2.0">
                      <searchID>%s</searchID><searchResultPosition>0</searchResultPosition><maxResults>1</maxResults>
                      <type>allVehicleList</type>
                    </LPListAuditSearchDescription>
                    """.formatted(id))), "LPListAuditSearchResult");
            if (!id.toString().equals(field(result, "searchID")) || !"true".equalsIgnoreCase(field(result, "responseStatus"))
                    || !List.of("OK", "MORE", "NO_MATCHES").contains(field(result, "responseStatusStrg"))
                    || result.getElementsByTagNameNS("*", "LicensePlateInfo").getLength() > 1
                    || integer(result, "numOfMatches") != result.getElementsByTagNameNS("*", "LicensePlateInfo").getLength()
                    || integer(result, "totalMatches") < integer(result, "numOfMatches")) throw new IOException();
        } catch (IOException ex) {
            Feature issue = failure(ex);
            return new PlateFeatures(new Feature(issue.status(), "Mövcud nömrələri yoxlamaq alınmadı. " + issue.reason()), name);
        }
        return new PlateFeatures(supported("Kamera ayrıca əlavə/redaktə imkanını bildirir və siyahı axtarışını qəbul edir. Sınaq nömrəsi yazılmayıb."), name);
    }

    private static Feature archive(DigestHttpClient client, boolean legacy) throws IOException, InterruptedException {
        UUID id = UUID.randomUUID();
        OffsetDateTime end = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        OffsetDateTime start = end.minusMinutes(5);
        String path = legacy ? "/ISAPI/Intelligent/vehicleRecognition/Search/channels/1" : "/ISAPI/ContentMgmt/search";
        try {
            String response = body(client.post(path, "application/xml; charset=UTF-8",
                    AnprArchiveSearch.query(id, 0, start, end, legacy, 1)));
            var page = AnprArchiveSearch.parse(response, id);
            if (page.records() > 1 || integer(xml(response, legacy ? "VehicleRecognitionSearchResult" : "CMSearchResult"), "numOfMatches") != page.records()) {
                throw new IOException();
            }
            if (page.captures().stream().anyMatch(row -> row.time().isBefore(start) || row.time().isAfter(end))) {
                return unknown("Kamera tələb olunan tarix aralığından kənar qeydlər qaytardı. Arxiv axtarışı təsdiqlənmədi.");
            }
            if (page.captures().stream().anyMatch(row -> row.plate() == null)) {
                return unknown("Arxiv cavabında nömrə sahəsi yoxdur. Davamiyyət üçün uyğun arxiv oxuması təsdiqlənmədi.");
            }
            return supported(page.records() == 0
                    ? "Arxiv axtarışı qəbul edildi; son 5 dəqiqədə qeyd yoxdur. Oktyabr qeydlərinin saxlanması bu yoxlama ilə təsdiqlənmir."
                    : "Arxivdən nümunə qeyd oxundu. Köhnə qeydlərin mövcudluğu yaddaşda saxlanma müddətindən asılıdır.");
        } catch (Unsupported | AnprArchiveSearch.UnsupportedSearch ex) {
            if (!legacy) return archive(client, true);
            return unsupported("Firmware Attendra-nın istifadə etdiyi ANPR arxiv axtarışlarını dəstəkləmir.");
        }
    }

    private static boolean options(JsonNode cap, String name, String value) {
        var options = cap.path(name).path("@opt");
        if (options.isArray()) for (var option : options) if (value.equals(option.asText())) return true;
        return false;
    }

    private static String body(HttpResponse<String> response) throws IOException {
        int status = response.statusCode();
        if (status == 404 || status == 405 || status == 501) throw new Unsupported();
        if (status == 401 || status == 403) throw new Denied();
        if (status != 200 || response.body() == null) throw new IOException();
        return response.body();
    }

    private static Element xml(String source, String expected) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var root = builder.parse(new InputSource(new StringReader(source))).getDocumentElement();
            if ("ResponseStatus".equals(root.getLocalName()) && "notSupport".equalsIgnoreCase(field(root, "subStatusCode"))) {
                throw new Unsupported();
            }
            if (!expected.equals(root.getLocalName())) throw new IOException();
            return root;
        } catch (IOException ex) { throw ex; }
        catch (Exception ex) { throw new IOException(); }
    }

    private static String field(Element element, String name) {
        var nodes = element.getElementsByTagNameNS("*", name);
        if (nodes.getLength() == 0) return "";
        var text = nodes.item(0).getTextContent().strip();
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    private static int integer(Element element, String name) throws IOException {
        try {
            int value = Integer.parseInt(field(element, name));
            if (value < 0) throw new IOException();
            return value;
        } catch (NumberFormatException ex) { throw new IOException(); }
    }

    private static Feature failure(Exception ex) {
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return unknown("Yoxlama dayandırıldı. Yenidən yoxlayın.");
        }
        if (ex instanceof Unsupported) return unsupported("Firmware bu ISAPI sorğusunu dəstəkləmir.");
        if (ex instanceof Denied) return unknown("Kamera giriş icazəsini rədd etdi. İstifadəçi və səlahiyyətləri yoxlayın.");
        return unknown("Kameradan etibarlı cavab alınmadı. Bağlantını yoxlayıb yenidən cəhd edin.");
    }

    private static Feature supported(String reason) { return new Feature("SUPPORTED", reason); }
    private static Feature unsupported(String reason) { return new Feature("UNSUPPORTED", reason); }
    private static Feature unknown(String reason) { return new Feature("UNKNOWN", reason); }
    private static class Unsupported extends IOException {}
    private static class Denied extends IOException {}
}
