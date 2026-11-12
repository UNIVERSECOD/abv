package com.abv.hrerpisapi.device.client;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.device.DigestHttpClient;
import com.abv.hrerpisapi.device.model.ParsedAcsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ISAPI REST client for UserInfo, CardInfo and AcsEvent endpoints.
 * A new {@link DigestHttpClient} is created per call (stateless/thread-safe).
 */
@Slf4j
@Service
public class IsapiClient {

    @org.springframework.beans.factory.annotation.Autowired
    private com.abv.hrerpisapi.device.DeviceTargetPolicy targetPolicy;

    private static final ObjectMapper OM = new ObjectMapper();

    // Device expects ISO8601 with offset (e.g. 2026-04-21T15:19:00+04:00)
    private static final DateTimeFormatter ISAPI_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    // DS-K1T series expects local datetime without offset (e.g. 2026-01-01T00:00:00)
    private static final DateTimeFormatter ISAPI_LOCAL_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final int ACS_EVENT_MAX_RESULTS_CAP = 30;
    private static final int MAX_HISTORY_PAGES = 200;
    private static final String ACS_EVENT_ENDPOINT = "/ISAPI/AccessControl/AcsEvent?format=json";
    static final String USER_INFO_DELETE_ENDPOINT =
            "/ISAPI/AccessControl/UserInfo/Delete?format=json";
    static final String FACE_DELETE_ENDPOINT =
            "/ISAPI/Intelligent/FDLib/FDSearch/Delete?format=json&FDID=1&faceLibType=blackFD";

    @Value("${acs.history.major:5}")
    private int historyMajor;

    @Value("${acs.history.minor:75}")
    private int historyMinor;

    // -----------------------------------------------------------------------
    // Card lookup
    // -----------------------------------------------------------------------

    public Optional<String> searchCardEmployeeNo(DeviceEntity device, String cardNo)
            throws IOException, InterruptedException {

        String body = """
                {"CardInfoSearchCond":{"searchID":"1","SearchResultPosition":0,"maxResults":1,\
                "CardInfo":{"cardNo":"%s"}}}""".formatted(cardNo);

        HttpResponse<String> resp = clientFor(device)
                .post("/ISAPI/AccessControl/CardInfo/Search?format=json",
                        "application/json", body);

        if (resp.statusCode() != 200) {
            log.warn("CardInfo/Search returned HTTP {} for device {}", resp.statusCode(), device.getId());
            return Optional.empty();
        }

        JsonNode root = OM.readTree(resp.body());
        JsonNode list = root.path("CardInfo");
        if (!list.isArray() || list.isEmpty()) return Optional.empty();

        String empNo = list.get(0).path("employeeNo").asText("");
        return empNo.isBlank() ? Optional.empty() : Optional.of(empNo);
    }

    // -----------------------------------------------------------------------
    // AcsEvent history search
    // -----------------------------------------------------------------------

    /**
     * Searches ACS event history. Sends beginSerialNo when afterSerialNo > 0
     * to allow firmware-level filtering. A local serialNo guard is applied as
     * an additional de-duplication safeguard for devices that ignore beginSerialNo.
     */
    public List<ParsedAcsEvent> searchAcsEvents(DeviceEntity device,
                                                OffsetDateTime startTime,
                                                long afterSerialNo,
                                                int maxResults)
            throws IOException, InterruptedException {

        ZoneOffset deviceOffset = OffsetDateTime.now().getOffset();

        OffsetDateTime startForDevice = startTime.withOffsetSameInstant(deviceOffset);
        OffsetDateTime endForDevice = OffsetDateTime.now().withOffsetSameInstant(deviceOffset);

        String start = formatIsapiDateTime(startForDevice);
        String end = formatIsapiDateTime(endForDevice);

        String searchId = UUID.randomUUID().toString();
        int cappedResults = Math.max(1, Math.min(maxResults, ACS_EVENT_MAX_RESULTS_CAP));

        List<ParsedAcsEvent> result = new ArrayList<>();
        int searchResultPosition = 0;
        boolean exceededPageLimit = true;
        int filteredBySerialGuard = 0;

        for (int page = 0; page < MAX_HISTORY_PAGES; page++) {
            String body = buildAcsEventBody(searchId, start, end, searchResultPosition, cappedResults);

            log.info("AcsEvent history request device={} endpoint={} searchID={} position={} maxResults={} major={} minor={} startTime={} endTime={}",
                    device.getId(), ACS_EVENT_ENDPOINT, searchId, searchResultPosition, cappedResults,
                    historyMajor, historyMinor, start, end);

            log.info("AcsEvent history request body: {}", body);

            HttpResponse<String> resp = clientFor(device)
                    .post(ACS_EVENT_ENDPOINT, "application/json", body);

            if (resp.statusCode() != 200) {
                if (isAcsEventHistoryNotSupported(resp.statusCode(), resp.body())) {
                    throw new AcsEventHistoryNotSupportedException(device.getId());
                }
                log.warn("AcsEvent history request failed: device={} endpoint={} status={} startTime={} endTime={} body={}",
                        device.getId(), ACS_EVENT_ENDPOINT, resp.statusCode(), start, end, snippet(resp.body()));
                throw new IOException("AcsEvent history returned HTTP " + resp.statusCode()
                        + " for device " + device.getId() + ": " + snippet(resp.body()));
            }

            JsonNode acsEvent = OM.readTree(resp.body()).path("AcsEvent");
            int numOfMatches = acsEvent.path("numOfMatches").asInt(0);

            String responseStatus = acsEvent.path("responseStatusStrg").asText("");
            int totalMatches = acsEvent.path("totalMatches").asInt(0);

            log.info("AcsEvent history response: device={} totalMatches={} numOfMatches={} responseStatusStrg={}",
                    device.getId(), totalMatches, numOfMatches, responseStatus);

            JsonNode events = acsEvent.path("InfoList");
            if (events.isArray()) {
                for (JsonNode node : events) {
                    long serialNo = node.path("serialNo").asLong(-1);

                    // Local serial guard (de-dup and ignore already-processed events)
                    if (afterSerialNo > 0 && serialNo > 0 && serialNo <= afterSerialNo) {
                        filteredBySerialGuard++;
                        continue;
                    }

                    try {
                        result.add(parseHistoryEvent(node));
                    } catch (Exception e) {
                        log.warn("Failed to parse history event serialNo={}: {}", serialNo, e.getMessage());
                    }
                }
            }

            if (numOfMatches <= 0 || !"MORE".equalsIgnoreCase(responseStatus)) {
                exceededPageLimit = false;
                break;
            }

            int nextPosition = searchResultPosition + numOfMatches;
            if (nextPosition <= searchResultPosition) {
                log.warn("Stopping AcsEvent history pagination due to non-advancing position: device={} position={} numOfMatches={}",
                        device.getId(), searchResultPosition, numOfMatches);
                break;
            }
            searchResultPosition = nextPosition;
        }

        if (exceededPageLimit) {
            log.warn("Stopped AcsEvent history pagination after reaching safety page limit: device={} pages={}.",
                    device.getId(), MAX_HISTORY_PAGES);
        }
        if (filteredBySerialGuard > 0) {
            log.info("AcsEvent history serial guard filtered {} event(s) for device={} afterSerialNo={}",
                    filteredBySerialGuard, device.getId(), afterSerialNo);
        }
        return result;
    }

    public AcsEventSearchResult searchAcsEventsOnDemand(DeviceEntity device, String requestBody)
            throws IOException, InterruptedException {
        HttpResponse<String> response = clientFor(device)
                .post(ACS_EVENT_ENDPOINT, "application/json", requestBody);
        return new AcsEventSearchResult(response.statusCode(), response.body());
    }

    // -----------------------------------------------------------------------
    // DS-K1T series device user management
    // -----------------------------------------------------------------------

    /**
     * Adds a user to a DS-K1T series device via UserInfo/Record endpoint.
     */
    public UserOperationResult addDeviceUser(DeviceEntity device,
                                             String employeeNo,
                                             String name,
                                             String userType,
                                             String gender,
                                             LocalDateTime beginTime,
                                             LocalDateTime endTime)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("UserInfo", buildUserInfoMap(employeeNo, name, userType, gender, beginTime, endTime)));

        HttpResponse<String> resp = clientFor(device)
                .post("/ISAPI/AccessControl/UserInfo/Record?format=json", "application/json", body);

        return toUserOperationResult(resp);
    }

    public Optional<String> findFaceUrlByEmployeeNo(DeviceEntity device, String employeeNo)
            throws IOException, InterruptedException {
        List<String> requestBodies = List.of(
                OM.writeValueAsString(Map.of(
                        "FaceFDLibSearchCond", Map.of(
                                "searchID", "search_face_" + employeeNo,
                                "searchResultPosition", 0,
                                "maxResults", 1,
                                "faceLibType", "blackFD",
                                "FDID", "1",
                                "FPID", employeeNo
                        ))),
                OM.writeValueAsString(Map.of(
                        "searchResultPosition", 0,
                        "maxResults", 1,
                        "faceLibType", "blackFD",
                        "FDID", "1",
                        "FPID", employeeNo
                ))
        );

        for (String body : requestBodies) {
            HttpResponse<String> resp = clientFor(device)
                    .post("/ISAPI/Intelligent/FDLib/FDSearch?format=json", "application/json", body);

            if (resp.statusCode() != 200) {
                log.warn("FDSearch returned HTTP {} for device {} employeeNo {} bodySnippet={}",
                        resp.statusCode(), device.getId(), employeeNo, snippet(resp.body()));
                continue;
            }

            JsonNode root = OM.readTree(resp.body());
            JsonNode list = extractFaceMatchList(root);
            if (!list.isArray() || list.isEmpty()) {
                continue;
            }

            String faceUrl = list.get(0).path("faceURL").asText("");
            if (!faceUrl.isBlank()) {
                return Optional.of(faceUrl);
            }
        }

        return Optional.empty();
    }

    /**
     * Pages through all face library records on the device via FDSearch (no FPID filter).
     * On Hikvision Access Control terminals, FPID is the person ID and matches UserInfo.employeeNo
     * (the same value this project stores as device_employee_no and uses when uploading faces).
     */
    public List<DeviceFaceRecord> searchAllFaceRecords(DeviceEntity device)
            throws IOException, InterruptedException {

        String searchId = "face-import-" + UUID.randomUUID();
        List<DeviceFaceRecord> result = new ArrayList<>();
        int position = 0;
        final int pageSize = 30;

        while (true) {
            Map<String, Object> cond = new LinkedHashMap<>();
            cond.put("searchID", searchId);
            cond.put("searchResultPosition", position);
            cond.put("maxResults", pageSize);
            cond.put("faceLibType", "blackFD");
            cond.put("FDID", "1");

            List<String> requestBodies = List.of(
                    OM.writeValueAsString(Map.of("FaceFDLibSearchCond", cond)),
                    OM.writeValueAsString(cond)
            );

            JsonNode list = null;
            String status = "";
            int numOfMatches = 0;
            boolean pageOk = false;

            for (String body : requestBodies) {
                HttpResponse<String> resp = clientFor(device)
                        .post("/ISAPI/Intelligent/FDLib/FDSearch?format=json", "application/json", body);
                if (resp.statusCode() != 200) {
                    log.warn("FDSearch (all) returned HTTP {} for device {} position {} bodySnippet={}",
                            resp.statusCode(), device.getId(), position, snippet(resp.body()));
                    continue;
                }

                JsonNode root = OM.readTree(resp.body());
                status = root.path("responseStatusStrg").asText("");
                if (status.isBlank()) {
                    status = root.path("FaceSearchResult").path("responseStatusStrg").asText("");
                }
                list = extractFaceMatchList(root);
                if (!list.isArray()) {
                    continue;
                }
                numOfMatches = root.path("numOfMatches").asInt(0);
                if (numOfMatches <= 0) {
                    numOfMatches = root.path("FaceSearchResult").path("numOfMatches").asInt(list.size());
                }
                pageOk = true;
                break;
            }

            if (!pageOk || list == null || !list.isArray() || list.isEmpty()
                    || status.toUpperCase().contains("NO")) {
                break;
            }

            int got = 0;
            for (JsonNode node : list) {
                String fpid = node.path("FPID").asText("").trim();
                if (fpid.isBlank()) {
                    fpid = node.path("fpid").asText("").trim();
                }
                if (fpid.isBlank()) {
                    continue;
                }
                String faceUrl = node.path("faceURL").asText("");
                if (faceUrl.isBlank()) {
                    faceUrl = node.path("faceUrl").asText("");
                }
                result.add(new DeviceFaceRecord(fpid, faceUrl.isBlank() ? null : faceUrl));
                got++;
            }

            if (numOfMatches <= 0) {
                numOfMatches = got;
            }

            if ("OK".equalsIgnoreCase(status) || numOfMatches < pageSize || got == 0) {
                break;
            }

            position += numOfMatches;
            if (position > 100_000) {
                log.warn("Aborting FDSearch pagination for device {} after position={}",
                        device.getId(), position);
                break;
            }
        }

        log.info("FDSearch (all) completed for device {} faceCount={}", device.getId(), result.size());
        return result;
    }

    private static JsonNode extractFaceMatchList(JsonNode root) {
        JsonNode list = root.path("MatchList");
        if (list.isArray()) {
            return list;
        }
        list = root.path("FaceSearchResult").path("MatchList");
        if (list.isArray()) {
            return list;
        }
        return root.path("MatchList");
    }

    public record DeviceFaceRecord(String fpid, String faceUrl) {
    }

    public Optional<byte[]> downloadFaceImage(DeviceEntity device, String faceUrl)
            throws IOException, InterruptedException {
        if (faceUrl == null || faceUrl.isBlank()) {
            return Optional.empty();
        }

        DigestHttpClient client = clientFor(device);
        try {
            HttpResponse<byte[]> resp = client.getBytesFromResourceUrl(faceUrl);
            if (isValidImageResponse(resp)) {
                return Optional.of(resp.body());
            }
            log.warn("ACS picture download failed for device {} url {} status {} contentType {}",
                    device.getId(),
                    faceUrl,
                    resp.statusCode(),
                    resp.headers().firstValue("Content-Type").orElse("<missing>"));
        } catch (IOException ex) {
            log.warn("ACS picture download error for device {} url {}: {}", device.getId(), faceUrl, ex.getMessage());
        }

        // Fallback: path-only candidates against configured device host
        List<String> candidatePaths = buildFaceImageCandidatePaths(faceUrl);
        for (String path : candidatePaths) {
            HttpResponse<byte[]> resp = client.getBytes(path);
            if (isValidImageResponse(resp)) {
                return Optional.of(resp.body());
            }

            log.warn("Face image download attempt failed for device {} path {} status {} contentType {}",
                    device.getId(),
                    path,
                    resp.statusCode(),
                    resp.headers().firstValue("Content-Type").orElse("<missing>"));
        }

        log.warn("Face image download failed for device {} url {}", device.getId(), faceUrl);
        return Optional.empty();
    }

    public Optional<byte[]> downloadFaceImageByEmployeeNo(DeviceEntity device, String employeeNo)
            throws IOException, InterruptedException {
        String path = "/ISAPI/Intelligent/FDLib/FDSetUp?format=json&FDID=1&FPID="
                + URLEncoder.encode(employeeNo, StandardCharsets.UTF_8);

        HttpResponse<byte[]> resp = clientFor(device).getBytes(path);
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
            log.warn("FDSetUp image download failed for device {} employeeNo {} status {}",
                    device.getId(), employeeNo, resp.statusCode());
            return Optional.empty();
        }

        Optional<byte[]> extractedImage = extractImageBytes(resp.body());
        if (extractedImage.isEmpty()) {
            log.warn("FDSetUp response did not contain image bytes for device {} employeeNo {} contentType {}",
                    device.getId(),
                    employeeNo,
                    resp.headers().firstValue("Content-Type").orElse("<missing>"));
        }
        return extractedImage;
    }

    /**
     * Updates a user on a DS-K1T series device via UserInfo/Modify endpoint.
     */
    public UserOperationResult updateDeviceUser(DeviceEntity device,
                                                String employeeNo,
                                                String name,
                                                String userType,
                                                String gender,
                                                LocalDateTime beginTime,
                                                LocalDateTime endTime)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("UserInfo", buildUserInfoMap(employeeNo, name, userType, gender, beginTime, endTime)));

        HttpResponse<String> resp = clientFor(device)
                .put("/ISAPI/AccessControl/UserInfo/Modify?format=json", "application/json", body);

        return toUserOperationResult(resp);
    }

    /**
     * Deletes a user from a DS-K1T series device.
     */
    public UserOperationResult deleteDeviceUser(DeviceEntity device, String employeeNo)
            throws IOException, InterruptedException {

        String body = buildDeviceUserDeleteBody(employeeNo);
        HttpResponse<String> resp = clientFor(device)
                .put(USER_INFO_DELETE_ENDPOINT, "application/json", body);

        return toUserOperationResult(resp);
    }

    static String buildDeviceUserDeleteBody(String employeeNo) throws IOException {
        return OM.writeValueAsString(Map.of(
                "UserInfoDelCond", Map.of(
                        "EmployeeNoList", List.of(Map.of("employeeNo", employeeNo)))));
    }

    /**
     * Checks whether a user with the given employeeNo exists on the device.
     */
    public boolean deviceUserExists(DeviceEntity device, String employeeNo)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("UserInfoSearchCond", Map.of(
                        "searchID", "search_" + employeeNo,
                        "searchResultPosition", 0,
                        "maxResults", 1,
                        "UserInfo", Map.of("employeeNo", employeeNo))));

        HttpResponse<String> resp = clientFor(device)
                .post("/ISAPI/AccessControl/UserInfo/Search?format=json", "application/json", body);

        if (resp.statusCode() != 200) return false;

        JsonNode list = extractUserInfoList(OM.readTree(resp.body()));
        return list.isArray() && !list.isEmpty();
    }

    /**
     * Pages through all persons enrolled on the device via UserInfo/Search.
     * Uses a stable searchID and advances {@code searchResultPosition} by
     * {@code numOfMatches} until the device reports OK / NO MATCHES.
     */
    public List<DevicePersonInfo> searchAllDeviceUsers(DeviceEntity device)
            throws IOException, InterruptedException {

        String searchId = "import-" + UUID.randomUUID();
        List<DevicePersonInfo> result = new ArrayList<>();
        int position = 0;
        final int pageSize = 30;

        while (true) {
            Map<String, Object> cond = new LinkedHashMap<>();
            cond.put("searchID", searchId);
            cond.put("searchResultPosition", position);
            cond.put("maxResults", pageSize);

            String body = OM.writeValueAsString(Map.of("UserInfoSearchCond", cond));
            HttpResponse<String> resp = clientFor(device)
                    .post("/ISAPI/AccessControl/UserInfo/Search?format=json", "application/json", body);

            if (resp.statusCode() != 200) {
                throw new IOException("UserInfo/Search returned HTTP " + resp.statusCode()
                        + " for device " + device.getId() + ": " + snippet(resp.body()));
            }

            JsonNode root = OM.readTree(resp.body());
            JsonNode search = root.path("UserInfoSearch");
            if (search.isMissingNode() || search.isNull()) {
                search = root;
            }

            String status = search.path("responseStatusStrg").asText("");
            JsonNode list = search.path("UserInfo");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }

            int got = 0;
            for (JsonNode node : list) {
                String employeeNo = node.path("employeeNo").asText("").trim();
                if (employeeNo.isBlank()) {
                    continue;
                }
                result.add(new DevicePersonInfo(
                        employeeNo,
                        node.path("name").asText(null),
                        node.path("userType").asText(null),
                        node.path("gender").asText(null),
                        node.path("Valid").path("beginTime").asText(null),
                        node.path("Valid").path("endTime").asText(null)
                ));
                got++;
            }

            int numOfMatches = search.path("numOfMatches").asInt(got);
            if (numOfMatches <= 0) {
                numOfMatches = got;
            }

            if ("OK".equalsIgnoreCase(status)
                    || status.toUpperCase().contains("NO")
                    || numOfMatches < pageSize
                    || got == 0) {
                break;
            }

            position += numOfMatches;
            if (position > 100_000) {
                log.warn("Aborting UserInfo/Search pagination for device {} after position={}",
                        device.getId(), position);
                break;
            }
        }

        return result;
    }

    public List<DevicePersonInfo> searchDeviceUsersSnapshot(DeviceEntity device) throws IOException, InterruptedException {
        String searchId = "reconcile-" + UUID.randomUUID();
        return DeviceUserSnapshotReader.read(position -> {
            String body = OM.writeValueAsString(Map.of("UserInfoSearchCond", Map.of(
                    "searchID", searchId, "searchResultPosition", position, "maxResults", 30)));
            HttpResponse<String> response = clientFor(device).post(
                    "/ISAPI/AccessControl/UserInfo/Search?format=json", "application/json", body);
            if (response.statusCode() != 200) throw new IOException("User snapshot unavailable");
            return OM.readTree(response.body());
        });
    }

    private static JsonNode extractUserInfoList(JsonNode root) {
        JsonNode nested = root.path("UserInfoSearch").path("UserInfo");
        if (nested.isArray()) {
            return nested;
        }
        return root.path("UserInfo");
    }

    public record DevicePersonInfo(
            String employeeNo,
            String name,
            String userType,
            String gender,
            String beginTime,
            String endTime
    ) {
    }

    /**
     * Uploads a face photo to a DS-K1T device via URL reference.
     */
    public UserOperationResult uploadFaceByUrl(DeviceEntity device, String employeeNo, String faceUrl)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("FaceData", Map.of(
                        "faceLibType", "normalFD",
                        "employeeNo", employeeNo,
                        "faceURL", faceUrl)));

        HttpResponse<String> resp = clientFor(device)
                .put("/ISAPI/AccessControl/Face/FaceData?format=json", "application/json", body);

        return toUserOperationResult(resp);
    }

    /**
     * Uploads a face photo to a DS-K1T device as binary multipart/form-data.
     */
    public UserOperationResult uploadFaceBinary(DeviceEntity device, String employeeNo, byte[] imageBytes)
            throws IOException, InterruptedException {

        String boundary = "----HikIsapiBoundary" + UUID.randomUUID().toString().replace("-", "");

        // JSON strukturunda sahə adı cihazın gözlədiyi formatda olmalıdır
        String jsonPart = "{\"faceLibType\":\"normalFD\",\"employeeNo\":\"" + employeeNo + "\"}";

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        // 1-ci HİSSƏ: JSON Məlumatı (Sahə adı: FacelibDataJSON)
        baos.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"FacelibDataJSON\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(jsonPart.getBytes(StandardCharsets.UTF_8));

        // 2-ci HİSSƏ: Şəkil Məlumatı (Sahə adı: FaceImage)
        baos.write(("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"FaceImage\"; filename=\"face.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(imageBytes);

        // SONLANDIRICI
        baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        // Endpoint URL-i düzgündür
        HttpResponse<String> resp = clientFor(device)
                .putBytes("/ISAPI/AccessControl/Face/FaceData?format=json",
                        "multipart/form-data; boundary=" + boundary, baos.toByteArray());

        return toUserOperationResult(resp);
    }

    public UserOperationResult uploadFaceToFDLib(DeviceEntity device, String employeeNo, byte[] imageBytes)
            throws IOException, InterruptedException {

        String boundary = "----HikIsapiBoundary" + UUID.randomUUID().toString().replace("-", "");

        // Keep the source picture as well as the recognition template on the terminal.
        String jsonPart = OM.writeValueAsString(Map.of(
                "faceLibType", "blackFD", "FDID", "1", "FPID", employeeNo,
                "saveFacePic", true));

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        // 1. JSON Hissəsi (Sahə adı: FaceFDLibJSON)
        baos.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"FaceFDLibJSON\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(jsonPart.getBytes(StandardCharsets.UTF_8));

        // 2. Şəkil Hissəsi (Sahə adı: FaceImage)
        baos.write(("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"FaceImage\"; filename=\"face.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(imageBytes);

        baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        // Network tabında gördüyünüz URL
        HttpResponse<String> resp = clientFor(device)
                .putBytes("/ISAPI/Intelligent/FDLib/FDSetUp?format=json",
                        "multipart/form-data; boundary=" + boundary, baos.toByteArray());

        return toUserOperationResult(resp);
    }

    public UserOperationResult deleteFaceFromFDLib(DeviceEntity device, String employeeNo)
            throws IOException, InterruptedException {
        String body = buildFaceDeleteBody(employeeNo);
        HttpResponse<String> resp = clientFor(device)
                .put(FACE_DELETE_ENDPOINT, "application/json", body);
        return toUserOperationResult(resp);
    }

    static String buildFaceDeleteBody(String employeeNo) throws IOException {
        return OM.writeValueAsString(Map.of(
                "FPID", List.of(Map.of("value", employeeNo))));
    }
    // -----------------------------------------------------------------------
    // Legacy user management (generic devices)
    // -----------------------------------------------------------------------

    public UserOperationResult addUser(DeviceEntity device, String userName, String password, String userType)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("UserInfo", Map.of("userName", userName, "password", password, "userType", userType)));

        HttpResponse<String> resp = clientFor(device)
                .post("/ISAPI/AccessControl/UserInfo/SetUp?format=json", "application/json", body);

        return toUserOperationResult(resp);
    }

    public UserOperationResult updateUser(DeviceEntity device, String userName, String password, String userType)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("UserInfo", Map.of("userName", userName, "password", password, "userType", userType)));

        HttpResponse<String> resp = clientFor(device)
                .put("/ISAPI/AccessControl/UserInfo/Modify?format=json", "application/json", body);

        return toUserOperationResult(resp);
    }

    public UserOperationResult deleteUser(DeviceEntity device, String userName)
            throws IOException, InterruptedException {

        String encodedUserName = URLEncoder.encode(userName, StandardCharsets.UTF_8);
        HttpResponse<String> resp = clientFor(device)
                .delete("/ISAPI/AccessControl/UserInfo/Delete?format=json&userName=" + encodedUserName, "application/json", "");

        return toUserOperationResult(resp);
    }

    public List<String> listUsers(DeviceEntity device)
            throws IOException, InterruptedException {

        HttpResponse<String> resp = clientFor(device)
                .get("/ISAPI/AccessControl/UserInfo/UserInfoList?format=json");

        if (resp.statusCode() != 200) {
            log.warn("UserInfo/UserInfoList returned HTTP {} for device {}", resp.statusCode(), device.getId());
            return List.of();
        }

        JsonNode root = OM.readTree(resp.body());
        JsonNode list = root.path("UserInfo");
        if (!list.isArray()) return List.of();

        List<String> userNames = new ArrayList<>();
        for (JsonNode node : list) {
            String userName = node.path("userName").asText("");
            if (!userName.isBlank()) userNames.add(userName);
        }
        return userNames;
    }

    public boolean userExists(DeviceEntity device, String userName)
            throws IOException, InterruptedException {

        String body = OM.writeValueAsString(
                Map.of("UserInfoSearchCond", Map.of(
                        "searchID", "1",
                        "SearchResultPosition", 0,
                        "maxResults", 1,
                        "UserInfo", Map.of("userName", userName))));

        HttpResponse<String> resp = clientFor(device)
                .post("/ISAPI/AccessControl/UserInfo/Search?format=json", "application/json", body);

        if (resp.statusCode() != 200) return false;

        JsonNode root = OM.readTree(resp.body());
        JsonNode list = root.path("UserInfo");
        return list.isArray() && !list.isEmpty();
    }

    private UserOperationResult toUserOperationResult(HttpResponse<String> resp) {
        boolean success = resp.statusCode() >= 200 && resp.statusCode() < 300;
        return new UserOperationResult(success, resp.statusCode(), snippet(resp.body()));
    }

    public record UserOperationResult(boolean success, int statusCode, String responseSnippet) {}

    static boolean isDeviceInfo(String body) {
        if (body == null) return false;
        try {
            JsonNode info = OM.readTree(body).path("DeviceInfo");
            return info.isObject() && (info.hasNonNull("model") || info.hasNonNull("serialNumber"));
        } catch (Exception ignored) {
            return body.contains("<DeviceInfo") && body.contains("</DeviceInfo>")
                    && (body.contains("<model>") || body.contains("<serialNumber>"));
        }
    }

    public DeviceStatusCheckResult checkDeviceStatus(DeviceEntity device) {
        try {
            HttpResponse<String> resp = clientFor(device).get(device.isAnpr()
                    ? "/ISAPI/System/deviceInfo" : "/ISAPI/System/deviceInfo?format=json");
            return new DeviceStatusCheckResult(
                    resp.statusCode() == 200 && isDeviceInfo(resp.body()),
                    resp.statusCode(),
                    snippet(resp.body()));
        } catch (Exception e) {
            log.info("Device status check failed for device {} ({}): {}", device.getId(), device.getIp(), e.getMessage());
            log.info("Device status check exception for device {}", device.getId(), e);
            return new DeviceStatusCheckResult(false, -1, snippet(e.getMessage()));
        }
    }

    public int searchAnprArchive(DeviceEntity device, OffsetDateTime start, OffsetDateTime end,
            java.util.function.Consumer<List<AnprArchiveSearch.Capture>> save) throws IOException, InterruptedException {
        var client = clientFor(device);
        try {
            return AnprArchiveSearch.readPages(start, end,
                    xml -> archiveResponse(client, "/ISAPI/ContentMgmt/search", xml), false, save);
        } catch (AnprArchiveSearch.UnsupportedSearch unsupported) {
            return AnprArchiveSearch.readPages(start, end,
                    xml -> archiveResponse(client, "/ISAPI/Intelligent/vehicleRecognition/Search/channels/1", xml), true, save);
        }
    }

    private String archiveResponse(DigestHttpClient client, String path, String xml) throws IOException, InterruptedException {
        var response = client.post(path, "application/xml", xml);
        if (response.statusCode() == 404 || response.statusCode() == 405
                || response.statusCode() == 501) throw new AnprArchiveSearch.UnsupportedSearch();
        if (response.statusCode() != 200) throw new IOException("ANPR archive search returned HTTP " + response.statusCode());
        return response.body();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> buildUserInfoMap(String employeeNo,
                                                 String name,
                                                 String userType,
                                                 String gender,
                                                 LocalDateTime beginTime,
                                                 LocalDateTime endTime) {
        LocalDateTime effectiveBegin = beginTime != null ? beginTime
                : LocalDateTime.parse("2026-04-27T00:00:00");
        LocalDateTime effectiveEnd = endTime != null ? endTime
                : LocalDateTime.parse("2036-04-26T23:59:59");

        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("enable", true);
        valid.put("beginTime", effectiveBegin.format(ISAPI_LOCAL_DT));
        valid.put("endTime", effectiveEnd.format(ISAPI_LOCAL_DT));
        valid.put("timeType", "local");

        String normalizedGender = HikvisionPayloadNormalizer.normalizeGender(gender);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("employeeNo", employeeNo);
        map.put("name", name);
        map.put("userType", userType != null ? userType : "normal");
        if (normalizedGender != null) {
            map.put("gender", normalizedGender);
        }
        map.put("localUIRight", false);
        map.put("maxOpenDoorTime", 0);
        map.put("Valid", valid);
        map.put("doorRight", "1");
        map.put("RightPlan", List.of(Map.of("doorNo", 1, "planTemplateNo", "1")));
        return map;
    }

    private ParsedAcsEvent parseHistoryEvent(JsonNode node) {
        long serialNo = node.path("serialNo").asLong(-1);
        String timeStr = node.path("time").asText(null);
        OffsetDateTime time = timeStr != null ? OffsetDateTime.parse(timeStr) : null;

        int major = node.path("major").asInt(node.path("majorEventType").asInt(-1));
        int minor = node.path("minor").asInt(node.path("subEventType").asInt(-1));
        String employeeNo = node.path("employeeNoString").asText("");
        String cardNo = node.path("cardNo").asText("");

        return new ParsedAcsEvent(serialNo, time, major, minor, employeeNo, cardNo, node.toString());
    }

    private boolean isAcsEventHistoryNotSupported(int statusCode, String responseBody) {
        if (statusCode == 404) return true;
        try {
            JsonNode root = OM.readTree(responseBody);
            String subStatusCode = root.path("subStatusCode").asText("");
            String statusString = root.path("statusString").asText("");
            return "notSupport".equalsIgnoreCase(subStatusCode)
                    || "Invalid Operation".equalsIgnoreCase(statusString);
        } catch (Exception ignored) {
            return false;
        }
    }

    static String formatIsapiDateTime(OffsetDateTime value) {
        String formatted = value.truncatedTo(ChronoUnit.SECONDS).format(ISAPI_DT);
        if (formatted.endsWith("Z")) {
            formatted = formatted.substring(0, formatted.length() - 1) + "+00:00";
        }
        return formatted;
    }


    private String buildAcsEventBody(String searchId,
                                     String start,
                                     String end,
                                     int searchResultPosition,
                                     int maxResults) {

        Map<String, Object> cond = new LinkedHashMap<>();
        cond.put("searchID", searchId);
        cond.put("searchResultPosition", searchResultPosition);
        cond.put("maxResults", maxResults);
        cond.put("major", historyMajor);
        cond.put("minor", historyMinor);
        cond.put("startTime", start);
        cond.put("endTime", end);

        try {
            return OM.writeValueAsString(Map.of("AcsEventCond", cond));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DigestHttpClient clientFor(DeviceEntity device) {
        targetPolicy.requireAllowed(device.getIp());
        return new DigestHttpClient(
                "http://" + device.getIp(),
                device.getUsername(),
                device.getPassword(),
                device.isAnpr());
    }

    private List<String> buildFaceImageCandidatePaths(String faceUrl) {
        String path = toDevicePath(faceUrl);
        List<String> candidates = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return candidates;
        }

        candidates.add(path);

        int webTokenIndex = path.indexOf("@WEB");
        if (webTokenIndex > 0) {
            String strippedPath = path.substring(0, webTokenIndex);
            if (!strippedPath.isBlank()) {
                candidates.add(strippedPath);
            }
        }

        String fileName = extractPictureFileName(faceUrl);
        if (fileName != null) {
            candidates.add("/ISAPI/AccessControl/AcsEvent/Pic?format=json&name="
                    + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        }

        return candidates;
    }

    private String extractPictureFileName(String faceUrl) {
        if (faceUrl == null || faceUrl.isBlank()) {
            return null;
        }
        String path = faceUrl;
        int slash = faceUrl.lastIndexOf('/');
        if (slash >= 0 && slash < faceUrl.length() - 1) {
            path = faceUrl.substring(slash + 1);
        }
        int at = path.indexOf('@');
        if (at > 0) {
            path = path.substring(0, at);
        }
        return path.isBlank() ? null : path;
    }

    private boolean isValidImageResponse(HttpResponse<byte[]> resp) {
        if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
            return false;
        }

        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (contentType.startsWith("image/")) {
            return true;
        }

        return isJpeg(resp.body()) || isPng(resp.body());
    }

    private Optional<byte[]> extractImageBytes(byte[] source) {
        if (source == null || source.length == 0) {
            return Optional.empty();
        }

        if (isJpeg(source) || isPng(source)) {
            return Optional.of(source);
        }

        int jpegStart = findJpegStart(source);
        if (jpegStart < 0) {
            return Optional.empty();
        }

        int jpegEnd = findJpegEnd(source, jpegStart);
        if (jpegEnd > jpegStart) {
            return Optional.of(java.util.Arrays.copyOfRange(source, jpegStart, jpegEnd + 1));
        }

        return Optional.of(java.util.Arrays.copyOfRange(source, jpegStart, source.length));
    }

    private int findJpegStart(byte[] source) {
        for (int i = 0; i < source.length - 1; i++) {
            if ((source[i] & 0xFF) == 0xFF && (source[i + 1] & 0xFF) == 0xD8) {
                return i;
            }
        }
        return -1;
    }

    private int findJpegEnd(byte[] source, int startIndex) {
        for (int i = Math.max(startIndex + 2, 0); i < source.length - 1; i++) {
            if ((source[i] & 0xFF) == 0xFF && (source[i + 1] & 0xFF) == 0xD9) {
                return i + 1;
            }
        }
        return -1;
    }

    private boolean isJpeg(byte[] body) {
        return body.length > 3
                && (body[0] & 0xFF) == 0xFF
                && (body[1] & 0xFF) == 0xD8
                && (body[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] body) {
        return body.length > 8
                && (body[0] & 0xFF) == 0x89
                && body[1] == 0x50
                && body[2] == 0x4E
                && body[3] == 0x47;
    }

    private String toDevicePath(String faceUrl) {
        if (faceUrl == null || faceUrl.isBlank()) {
            return faceUrl;
        }
        if (faceUrl.startsWith("http://") || faceUrl.startsWith("https://")) {
            int schemeSep = faceUrl.indexOf("://");
            int hostStart = schemeSep + 3;
            int pathStart = faceUrl.indexOf('/', hostStart);
            if (pathStart < 0) {
                return "/";
            }
            return faceUrl.substring(pathStart);
        }
        return faceUrl.startsWith("/") ? faceUrl : "/" + faceUrl;
    }

    private String snippet(String raw) {
        if (raw == null) return "";
        String normalized = raw.replace("\n", " ").replace("\r", " ").trim();
        int max = 300;
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }

    public record DeviceStatusCheckResult(boolean online, int statusCode, String responseSnippet) {}

    public static class AcsEventHistoryNotSupportedException extends RuntimeException {
        public AcsEventHistoryNotSupportedException(Long deviceId) {
            super("AcsEvent history is not supported for device " + deviceId);
        }
    }

    public record AcsEventSearchResult(int statusCode, String body) {
    }
}
