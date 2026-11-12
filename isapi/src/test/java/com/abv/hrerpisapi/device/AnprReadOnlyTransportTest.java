package com.abv.hrerpisapi.device;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnprReadOnlyTransportTest {
    @Test
    void confirmedRemovalSendsOnlyOneIdToDigestProtectedEndpoint() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        var endpoint = "/ISAPI/Traffic/channels/1/DelLicensePlateAuditData";
        server.createContext(endpoint, request -> {
            try (request) {
                String auth = request.getRequestHeaders().getFirst("Authorization");
                if (auth == null) {
                    request.getResponseHeaders().set("WWW-Authenticate",
                            "Digest realm=\"camera\", nonce=\"fixture\", qop=\"auth\"");
                    request.sendResponseHeaders(401, -1);
                } else {
                    received.set(request.getRequestMethod() + " " + auth + "\n"
                            + new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    request.sendResponseHeaders(204, -1);
                }
            }
        });
        server.start();
        try {
            var client = new DigestHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture", true);
            assertThatThrownBy(() -> client.put(endpoint + "?format=json", "application/json", "{}"))
                    .isInstanceOf(IOException.class);
            assertThat(received.get()).isNull();
            assertThat(client.deleteConfirmedAnprPlateRecord("7").statusCode()).isEqualTo(204);
            assertThat(received.get()).startsWith("PUT Digest ")
                    .contains("uri=\"" + endpoint + "?format=json\"")
                    .endsWith("{\"id\":[\"7\"],\"deleteAllEnabled\":false}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void digestStatusUsesTheCompleteRequestTarget() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authorization = new AtomicReference<String>();
        server.createContext("/ISAPI/System/deviceInfo", request -> {
            String auth = request.getRequestHeaders().getFirst("Authorization");
            if (auth == null) {
                request.getResponseHeaders().set("WWW-Authenticate",
                        "Digest realm=\"camera\", nonce=\"fixture\", qop=\"auth\"");
                request.sendResponseHeaders(401, -1);
            } else {
                authorization.set(auth);
                byte[] body = "<DeviceInfo><model>fixture</model></DeviceInfo>".getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/xml");
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
            request.close();
        });
        server.start();
        try {
            var client = new DigestHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture", true);
            var response = client.get("/ISAPI/System/deviceInfo?format=json");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(authorization.get()).contains("uri=\"/ISAPI/System/deviceInfo?format=json\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void allowsOnlyExactStatusAndArchiveSearchRoutes() {
        assertThat(DigestHttpClient.isCameraRead("GET", "/ISAPI/System/deviceInfo?format=json")).isTrue();
        assertThat(DigestHttpClient.isCameraRead("GET", "/ISAPI/System/deviceInfo")).isTrue();
        assertThat(DigestHttpClient.isCameraRead("POST", "/ISAPI/ContentMgmt/search")).isTrue();
        assertThat(DigestHttpClient.isCameraRead("POST", "/ISAPI/Intelligent/vehicleRecognition/Search/channels/1")).isTrue();
        assertThat(DigestHttpClient.isCameraRead("POST", "/ISAPI/Traffic/ANPR/alarmHttpPushProtocol")).isFalse();
        assertThat(DigestHttpClient.isCameraRead("PUT", "/ISAPI/ContentMgmt/search")).isFalse();
    }

    @Test
    void blockedRequestsThrowBeforeAnyNetworkConnection() {
        var camera = new DigestHttpClient("http://192.0.2.1", "fixture", "fixture", true);
        assertThatThrownBy(() -> camera.put("/ISAPI/Traffic/ANPR/alarmHttpPushProtocol", "application/xml", "<fixture/>"))
                .isInstanceOf(IOException.class).hasMessageContaining("only permits");
        assertThatThrownBy(() -> camera.delete("/ISAPI/AccessControl/UserInfo/Delete", "application/xml", ""))
                .isInstanceOf(IOException.class).hasMessageContaining("only permits");
        assertThatThrownBy(() -> camera.post("/ISAPI/AccessControl/UserInfo/Record", "application/xml", "<fixture/>"))
                .isInstanceOf(IOException.class).hasMessageContaining("only permits");
    }
}
