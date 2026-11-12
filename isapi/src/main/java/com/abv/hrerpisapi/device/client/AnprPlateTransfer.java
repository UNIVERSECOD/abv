package com.abv.hrerpisapi.device.client;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.device.DeviceTargetPolicy;
import com.abv.hrerpisapi.device.DigestHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.abv.hrerpisapi.device.DigestHttpClient.ANPR_PLATE_CAPABILITIES;
import static com.abv.hrerpisapi.device.DigestHttpClient.ANPR_PLATE_SEARCH;
import static com.abv.hrerpisapi.device.DigestHttpClient.ANPR_PLATE_DELETE_CAPABILITIES;

@Service
@RequiredArgsConstructor
public class AnprPlateTransfer {
    private static final int PAGE_SIZE = 200;
    private static final int MAX_RECORDS = 20_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DeviceTargetPolicy targets;

    public record Plan(String operation, String listType, String fingerprint) {}
    public record RemovalPlan(String recordId, String listType, String fingerprint) {}
    record Prepared(ObjectNode record, Plan plan) {}
    record Search(Element match, Set<String> ids) {}

    public Plan preview(DeviceEntity device, String plate, String name) throws IOException, InterruptedException {
        return prepare(client(device), plate, name).plan();
    }

    public void apply(DeviceEntity device, String plate, String name, String fingerprint)
            throws IOException, InterruptedException {
        apply(client(device), plate, name, fingerprint);
    }

    public RemovalPlan previewRemoval(DeviceEntity device, String plate, String name)
            throws IOException, InterruptedException {
        return prepareRemoval(client(device), plate, name);
    }

    public void remove(DeviceEntity device, String plate, String name, String fingerprint)
            throws IOException, InterruptedException {
        remove(client(device), plate, name, fingerprint);
    }

    static void remove(DigestHttpClient client, String plate, String name, String fingerprint)
            throws IOException, InterruptedException {
        var plan = prepareRemoval(client, plate, name);
        if (plan.fingerprint() == null || !plan.fingerprint().equals(fingerprint)) {
            throw new IOException("Kamera və ya nömrə qeydi dəyişib. Yenidən yoxlayın.");
        }
        var response = JSON.readTree(ok(client.deleteConfirmedAnprPlateRecord(plan.recordId())));
        if (response == null || !response.path("statusCode").isIntegralNumber()
                || response.path("statusCode").intValue() != 1 || search(client, plate).match() != null) {
            throw new IOException("Kamerada silinmə təsdiqlənmədi. Kamera siyahısını yenidən yoxlayın.");
        }
    }

    static RemovalPlan prepareRemoval(DigestHttpClient client, String plate, String name)
            throws IOException, InterruptedException {
        if (plate == null || !plate.matches("[A-Z0-9]{2,32}") || name == null || name.isBlank()) {
            throw new IOException("Əməkdaşın adı və avtomobil nömrəsi etibarlı olmalıdır.");
        }
        var info = xml(ok(client.get("/ISAPI/System/deviceInfo")), "DeviceInfo");
        String serial = field(info, "serialNumber");
        if (serial.isBlank()) throw new IOException("Kameranın kimliyini təsdiqləmək alınmadı.");
        var match = search(client, plate).match();
        if (match == null) return new RemovalPlan(null, null, null);
        String id = field(match, "id");
        String list = listType(match);
        if (!"allowList".equals(list) || !name.equals(field(match, "name")) || id.length() > 16) {
            throw new IOException("Kameradakı qeyd əməkdaşın icazə siyahısına uyğun gəlmir.");
        }
        var capabilities = JSON.readTree(ok(client.get(ANPR_PLATE_DELETE_CAPABILITIES)));
        if (capabilities == null || capabilities.path("id").path("@size").asInt(0) < 1) {
            throw new IOException("Kamera qeyd ID-si ilə silməni təsdiqləmədi.");
        }
        var fields = JSON.createObjectNode();
        var children = match.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element field) {
                if (fields.has(field.getLocalName())) throw new IOException("Kamera qeydi ziddiyyətli sahələr daşıyır.");
                fields.put(field.getLocalName(), field.getTextContent());
            }
        }
        return new RemovalPlan(id, list, hash(serial + "\n" + fields));
    }

    static void apply(DigestHttpClient client, String plate, String name, String fingerprint)
            throws IOException, InterruptedException {
        var prepared = prepare(client, plate, name);
        if (!prepared.plan().fingerprint().equals(fingerprint)) {
            throw new IOException("Kamera və ya nömrə qeydi dəyişib. Yenidən yoxlayın.");
        }
        if ("unchanged".equals(prepared.plan().operation())) return;
        ObjectNode body = JSON.createObjectNode();
        body.putArray("LicensePlateInfoList").add(prepared.record());
        var response = JSON.readTree(ok(client.putConfirmedAnprPlateRecord(body.toString())));
        if (response == null || !response.path("statusCode").isIntegralNumber()
                || response.path("statusCode").intValue() != 1) {
            throw new IOException("Kamera nömrə qeydini qəbul etmədi.");
        }
        var verified = search(client, plate).match();
        if (verified == null || !name.equals(field(verified, "name"))
                || !prepared.plan().listType().equals(listType(verified))) {
            throw new IOException("Kameraya yazılan nömrəni təsdiqləmək alınmadı. Siyahını yenidən yoxlayın.");
        }
    }

    private DigestHttpClient client(DeviceEntity device) throws IOException {
        if (!device.isAnpr() || !device.isEnabled()) throw new IOException("Aktiv ANPR kamera seçin.");
        targets.requireAllowed(device.getIp());
        return new DigestHttpClient("http://" + device.getIp(), device.getUsername(), device.getPassword(), true);
    }

    static Prepared prepare(DigestHttpClient client, String plate, String name) throws IOException, InterruptedException {
        if (plate == null || !plate.matches("[A-Z0-9]{2,32}") || name == null || name.isBlank()
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Əməkdaşın adı və avtomobil nömrəsi etibarlı olmalıdır.");
        }
        var deviceInfo = xml(ok(client.get("/ISAPI/System/deviceInfo")), "DeviceInfo");
        String serial = field(deviceInfo, "serialNumber");
        if (serial.isBlank()) throw new IOException("Kameranın kimliyini təsdiqləmək alınmadı.");
        var capabilityBody = JSON.readTree(ok(client.get(ANPR_PLATE_CAPABILITIES)));
        if (capabilityBody == null) throw new IOException("Kamera nömrə yazma imkanını təsdiqləmədi.");
        var capabilities = capabilityBody.path("LicensePlateInfoCap");
        int nameMax = capabilities.path("name").path("@max").asInt(0);
        int plateMax = capabilities.path("licensePlate").path("@max").asInt(0);
        if (nameMax <= 0 || plateMax <= 0) throw new IOException("Kamera nömrə və ad yazma imkanını təsdiqləmədi.");
        if (name.getBytes(StandardCharsets.UTF_8).length > nameMax || plate.length() > plateMax) {
            throw new IOException("Ad, soyad və ya nömrə kameranın uzunluq həddini aşır.");
        }
        var result = search(client, plate);
        var existing = result.match();
        String list = existing == null ? "allowList" : listType(existing);
        String oldName = existing == null ? "" : field(existing, "name");
        String operation = existing == null ? "add" : oldName.equals(name) ? "unchanged" : "modify";
        ObjectNode record = JSON.createObjectNode();
        if (existing != null) {
            copy(existing, record, "createTime", "effectiveStartDate", "cardID", "cardNo",
                    "certificateType", "certificateNumber", "virtualParkingNum", "plateDescription", "groupName");
            String effective = field(existing, "effectiveDateTime");
            if (effective.isBlank()) effective = field(existing, "effectiveTime");
            if (!effective.isBlank()) record.put("effectiveTime", effective);
        }
        String id = existing == null ? freeId(result.ids(), capabilities.path("id")) : field(existing, "id");
        if (id.isBlank() || id.length() > 16) throw new IOException("Kamera qeydinin identifikatoru etibarlı deyil.");
        record.put("id", id);
        record.put("listType", list);
        record.put("LicensePlate", existing == null ? plate : field(existing, "LicensePlate"));
        record.put("name", name);
        if (!"unchanged".equals(operation)) {
            String color = existing == null ? "unknown" : field(existing, "plateColor");
            String type = existing == null ? "unknown" : field(existing, "plateType");
            if (existing != null && type.isBlank()) type = field(existing, "plateCategory");
            if (!supports(capabilities, "operationType", operation) || !supports(capabilities, "listType", list)
                    || !supports(capabilities, "plateColor", color) || !supports(capabilities, "plateType", type)) {
                throw new IOException("Kamera bu əməliyyatı və ya qeydin mövcud sahələrini dəstəkləmir.");
            }
            record.put("operationType", operation);
            record.put("plateColor", color);
            record.put("plateType", type);
        }
        String fingerprint = hash(serial + "\n" + record + "\n" + oldName);
        return new Prepared(record, new Plan(operation, list, fingerprint));
    }

    private static Search search(DigestHttpClient client, String plate) throws IOException, InterruptedException {
        String searchId = UUID.randomUUID().toString();
        var ids = new HashSet<String>();
        Element match = null;
        Integer total = null;
        for (int offset = 0; offset <= MAX_RECORDS;) {
            var body = """
                    <LPListAuditSearchDescription xmlns="http://www.isapi.org/ver20/XMLSchema" version="2.0">
                      <searchID>%s</searchID><searchResultPosition>%d</searchResultPosition><maxResults>%d</maxResults>
                      <type>allVehicleList</type>
                    </LPListAuditSearchDescription>
                    """.formatted(searchId, offset, PAGE_SIZE);
            var root = xml(ok(client.post(ANPR_PLATE_SEARCH, "application/xml; charset=UTF-8", body)),
                    "LPListAuditSearchResult");
            if (!searchId.equals(field(root, "searchID")) || !"true".equalsIgnoreCase(field(root, "responseStatus"))) {
                throw new IOException("Kamera nömrə axtarışını təsdiqləmədi.");
            }
            String status = field(root, "responseStatusStrg");
            var rows = root.getElementsByTagNameNS("*", "LicensePlateInfo");
            int count = rows.getLength();
            int reported = number(root, "totalMatches");
            if (reported < 0 || reported > MAX_RECORDS || (total != null && total != reported)
                    || count != number(root, "numOfMatches") || count > PAGE_SIZE || offset + count > reported) {
                throw new IOException("Kamera siyahısının səhifələri tam oxunmadı.");
            }
            total = reported;
            for (int i = 0; i < count; i++) {
                var row = (Element) rows.item(i);
                String id = field(row, "id");
                String number = field(row, "LicensePlate");
                if (id.isBlank() || number.isBlank() || !ids.add(id)) throw new IOException("Kamera siyahısında ziddiyyətli qeyd var.");
                if (!plate.equals(number.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", ""))) continue;
                if (match != null) throw new IOException("Kamerada eyni nömrə üçün birdən çox qeyd var.");
                match = row;
            }
            offset += count;
            if (("OK".equals(status) || "NO_MATCHES".equals(status)) && offset == total) return new Search(match, ids);
            if (!"MORE".equals(status) || count == 0 || offset >= total) break;
        }
        throw new IOException("Kamera nömrə siyahısı tam oxunmadı.");
    }

    private static String freeId(Set<String> ids, JsonNode cap) throws IOException {
        int max = Math.min(cap.path("@max").asInt(0), 16);
        int min = Math.max(1, cap.path("@min").asInt(1));
        var used = new HashSet<String>();
        for (String id : ids) used.add(id.replaceFirst("^0+(?!$)", ""));
        for (int candidate = 1; candidate <= ids.size() + 1; candidate++) {
            String plain = Integer.toString(candidate);
            if (plain.length() <= max && !used.contains(plain)) {
                return "0".repeat(Math.max(0, min - plain.length())) + plain;
            }
        }
        throw new IOException("Kamerada yeni qeyd üçün boş identifikator təsdiqlənmədi.");
    }

    private static int number(Element element, String name) {
        try { return Integer.parseInt(field(element, name)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static void copy(Element source, ObjectNode destination, String... names) {
        for (String name : names) {
            String value = field(source, name);
            if (!value.isBlank()) destination.put(name, value);
        }
    }

    private static boolean supports(JsonNode capabilities, String field, String value) {
        for (var option : capabilities.path(field).path("@opt")) {
            if (option.asText().equals(value)) return true;
        }
        return false;
    }

    private static String listType(Element record) throws IOException {
        return switch (field(record, "type")) {
            case "whitelist", "allowList" -> "allowList";
            case "blackList", "blockList" -> "blockList";
            default -> throw new IOException("Kameradakı nömrə qeydinin siyahı növü məlum deyil.");
        };
    }

    private static Element xml(String body, String expected) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var root = builder.parse(new InputSource(new StringReader(body))).getDocumentElement();
            if (!expected.equals(root.getLocalName())) throw new IOException("Kamera gözlənilən cavabı qaytarmadı.");
            return root;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Kameranın XML cavabını oxumaq alınmadı.");
        }
    }

    private static String field(Element node, String key) {
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (key.equals(child.getLocalName())) return child.getTextContent().strip();
        }
        return "";
    }

    private static String ok(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) throw new IOException("Kamera sorğunu qəbul etmədi (HTTP " + response.statusCode() + ").");
        return response.body();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
