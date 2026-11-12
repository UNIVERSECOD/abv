package com.abv.hrerpisapi.device.client;

import com.abv.hrerpisapi.device.DigestHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnprCapabilitiesTest {
    private static final String INFO = "<DeviceInfo><model>iDS-2CD7A26G0/P-IZHS</model><firmwareVersion>fixture</firmwareVersion></DeviceInfo>";
    private static final String CAPS = """
            {"LicensePlateInfoCap":{"id":{"@max":16},"licensePlate":{"@max":32},"name":{"@max":32},
              "operationType":{"@opt":["add","modify"]},"listType":{"@opt":["allowList"]},
              "plateColor":{"@opt":["unknown"]},"plateType":{"@opt":["unknown"]}}}
            """;

    @Test
    void digestProbeReadsOnlyCapabilitiesAndOneRecordWithoutWritingOrReadingWholeArchive() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            String method = exchange.getRequestMethod();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(method + " " + path);
            if (auth == null) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Digest realm=\"fixture\", nonce=\"fixture\", qop=\"auth\"");
                exchange.sendResponseHeaders(401, -1);
            } else {
                assertThat(auth).contains("uri=\"" + path + "\"");
                String response;
                if (method.equals("GET") && path.equals("/ISAPI/System/deviceInfo")) response = INFO;
                else if (method.equals("GET") && path.equals(DigestHttpClient.ANPR_PLATE_CAPABILITIES)) response = CAPS;
                else if (method.equals("POST") && path.equals(DigestHttpClient.ANPR_PLATE_SEARCH)) {
                    assertThat(request).contains("<maxResults>1</maxResults>");
                    response = plateResult(request);
                } else if (method.equals("POST") && path.equals("/ISAPI/ContentMgmt/search")) {
                    assertThat(request).contains("<maxResults>1</maxResults>", "<trackID>103</trackID>", "vehicleDetection");
                    response = archiveResult(request, "CMSearchResult");
                } else throw new AssertionError("Unexpected camera operation: " + method + " " + path);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            var result = AnprCapabilities.inspect(new DigestHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture", true));
            assertThat(result.model()).isEqualTo("iDS-2CD7A26G0/P-IZHS");
            assertThat(result.plateWrite().status()).isEqualTo("SUPPORTED");
            assertThat(result.archiveRead().status()).isEqualTo("SUPPORTED");
            assertThat(result.ownerName().status()).isEqualTo("UNKNOWN");
            assertThat(result.archiveRead().reason()).contains("qeyd yoxdur", "təsdiqlənmir");
            assertThat(requests).hasSize(8).allMatch(value -> value.startsWith("GET ") || value.startsWith("POST "));
        } finally { server.stop(0); }
    }

    @Test
    void unsupportedWritingDoesNotPreventArchiveCheck() throws Exception {
        var client = client();
        when(client.get(DigestHttpClient.ANPR_PLATE_CAPABILITIES)).thenAnswer(call -> http(404, "secret camera body"));
        var result = AnprCapabilities.inspect(client);
        assertThat(result.plateWrite().status()).isEqualTo("UNSUPPORTED");
        assertThat(result.archiveRead().status()).isEqualTo("SUPPORTED");
        verify(client, never()).putConfirmedAnprPlateRecord(anyString());
    }

    @Test
    void rejectedCredentialsAndMalformedResponsesAreNotReportedAsUnsupportedFirmware() throws Exception {
        for (int status : List.of(401, 403, 200)) {
            var client = client();
            when(client.get(DigestHttpClient.ANPR_PLATE_CAPABILITIES)).thenAnswer(call -> http(status, "private malformed body"));
            var result = AnprCapabilities.inspect(client);
            assertThat(result.plateWrite().status()).isEqualTo("UNKNOWN");
            assertThat(result.plateWrite().reason()).doesNotContain("private", "malformed");
            assertThat(result.archiveRead().status()).isEqualTo("SUPPORTED");
        }
    }

    @Test
    void failedIdentityDoesNotProbeOtherEndpoints() throws Exception {
        var client = mock(DigestHttpClient.class);
        when(client.get("/ISAPI/System/deviceInfo")).thenThrow(new IOException("secret"));
        var result = AnprCapabilities.inspect(client);
        assertThat(result.plateWrite().status()).isEqualTo("UNKNOWN");
        assertThat(result.archiveRead().status()).isEqualTo("UNKNOWN");
        verify(client).get("/ISAPI/System/deviceInfo");
        verifyNoMoreInteractions(client);
    }

    @Test
    void archiveFallbackIsOneReadAndUnsupportedIsExplicit() throws Exception {
        var client = client();
        when(client.post(eq("/ISAPI/ContentMgmt/search"), anyString(), anyString()))
                .thenAnswer(call -> http(200, "<ResponseStatus><subStatusCode>notSupport</subStatusCode></ResponseStatus>"));
        when(client.post(eq("/ISAPI/Intelligent/vehicleRecognition/Search/channels/1"), anyString(), anyString()))
                .thenAnswer(call -> http(200, archiveResult(call.getArgument(2), "VehicleRecognitionSearchResult")));
        assertThat(AnprCapabilities.inspect(client).archiveRead().status()).isEqualTo("SUPPORTED");
        when(client.post(eq("/ISAPI/Intelligent/vehicleRecognition/Search/channels/1"), anyString(), anyString()))
                .thenAnswer(call -> http(404, ""));
        assertThat(AnprCapabilities.inspect(client).archiveRead().status()).isEqualTo("UNSUPPORTED");
    }

    @Test
    void capabilityLimitsAndMissingNameAreExplained() throws Exception {
        var client = client();
        when(client.get(DigestHttpClient.ANPR_PLATE_CAPABILITIES))
                .thenAnswer(call -> http(200, CAPS.replace("\"add\",\"modify\"", "\"add\"").replace("\"name\":{\"@max\":32},", "")));
        var result = AnprCapabilities.inspect(client);
        assertThat(result.plateWrite().status()).isEqualTo("UNSUPPORTED");
        assertThat(result.plateWrite().reason()).contains("redaktə");
        assertThat(result.ownerName().status()).isEqualTo("UNSUPPORTED");
        verify(client, never()).post(eq(DigestHttpClient.ANPR_PLATE_SEARCH), anyString(), anyString());
    }

    private static DigestHttpClient client() throws Exception {
        var client = mock(DigestHttpClient.class);
        when(client.get("/ISAPI/System/deviceInfo")).thenAnswer(call -> http(200, INFO));
        when(client.get(DigestHttpClient.ANPR_PLATE_CAPABILITIES)).thenAnswer(call -> http(200, CAPS));
        when(client.post(eq(DigestHttpClient.ANPR_PLATE_SEARCH), anyString(), anyString()))
                .thenAnswer(call -> http(200, plateResult(call.getArgument(2))));
        when(client.post(eq("/ISAPI/ContentMgmt/search"), anyString(), anyString()))
                .thenAnswer(call -> http(200, archiveResult(call.getArgument(2), "CMSearchResult")));
        return client;
    }

    private static String plateResult(String request) {
        return "<LPListAuditSearchResult><searchID>" + searchId(request) + "</searchID><responseStatus>true</responseStatus>"
                + "<responseStatusStrg>NO_MATCHES</responseStatusStrg><numOfMatches>0</numOfMatches><totalMatches>0</totalMatches></LPListAuditSearchResult>";
    }

    private static String archiveResult(String request, String root) {
        return "<" + root + "><searchID>" + searchId(request) + "</searchID><responseStatus>true</responseStatus>"
                + "<responseStatusStrg>NO MATCHES</responseStatusStrg><numOfMatches>0</numOfMatches></" + root + ">";
    }

    private static String searchId(String request) {
        var match = Pattern.compile("<searchID>([^<]+)</searchID>").matcher(request);
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> http(int status, String body) {
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
