package com.abv.hrerpisapi.device.client;

import com.abv.hrerpisapi.device.DigestHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static com.abv.hrerpisapi.device.DigestHttpClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnprPlateTransferTest {
    private static final String NAME = "Sınaq Əməkdaş";
    private static final String PLATE = "10AB123";

    @Test
    void removalPreviewIsReadOnlyAndChangedRecordBlocksConfirmation() throws Exception {
        var client = mock(DigestHttpClient.class);
        var rows = new AtomicReference<>(row("7", PLATE, NAME, "whitelist"));
        camera(client, rows);
        var capabilities = http("{\"id\":{\"@size\":10000}}");
        when(client.get(ANPR_PLATE_DELETE_CAPABILITIES)).thenReturn(capabilities);
        var preview = AnprPlateTransfer.prepareRemoval(client, PLATE, NAME);
        assertThat(preview.recordId()).isEqualTo("7");
        assertThat(preview.listType()).isEqualTo("allowList");
        rows.set(rows.get().replace("card-fixture", "other-card"));
        assertThatThrownBy(() -> AnprPlateTransfer.remove(client, PLATE, NAME, preview.fingerprint()))
                .isInstanceOf(IOException.class);
        verify(client, never()).deleteConfirmedAnprPlateRecord(anyString());
    }

    @Test
    void confirmedRemovalDeletesOnlyThePreviewedIdAndVerifiesAbsence() throws Exception {
        var client = mock(DigestHttpClient.class);
        var rows = new AtomicReference<>(row("7", PLATE, NAME, "whitelist"));
        camera(client, rows);
        var capabilities = http("{\"id\":{\"@size\":10000}}");
        when(client.get(ANPR_PLATE_DELETE_CAPABILITIES)).thenReturn(capabilities);
        var success = http("{\"statusCode\":1}");
        when(client.deleteConfirmedAnprPlateRecord("7")).thenAnswer(call -> {
            rows.set(row("8", "OTHER1", "Other", "whitelist"));
            return success;
        });
        var preview = AnprPlateTransfer.prepareRemoval(client, PLATE, NAME);
        AnprPlateTransfer.remove(client, PLATE, NAME, preview.fingerprint());
        assertThat(AnprPlateTransfer.prepareRemoval(client, PLATE, NAME).recordId()).isNull();
        verify(client).deleteConfirmedAnprPlateRecord("7");
    }

    @Test
    void addsOneRecordAfterConfirmationAndVerifiesItFromCamera() throws Exception {
        var client = mock(DigestHttpClient.class);
        var rows = new AtomicReference<>(row("1", "OTHER1", "Other", "whitelist"));
        camera(client, rows);
        when(client.putConfirmedAnprPlateRecord(anyString())).thenAnswer(call -> {
            var record = new ObjectMapper().readTree(call.<String>getArgument(0)).path("LicensePlateInfoList");
            assertThat(record.size()).isEqualTo(1);
            assertThat(record.get(0).path("id").asText()).isEqualTo("2");
            assertThat(record.get(0).path("LicensePlate").asText()).isEqualTo(PLATE);
            assertThat(record.get(0).path("name").asText()).isEqualTo(NAME);
            assertThat(record.get(0).path("operationType").asText()).isEqualTo("add");
            rows.set(rows.get() + row("2", PLATE, NAME, "whitelist"));
            return http("{\"statusCode\":1}");
        });
        var plan = AnprPlateTransfer.prepare(client, PLATE, NAME).plan();
        verify(client, never()).putConfirmedAnprPlateRecord(anyString());
        AnprPlateTransfer.apply(client, PLATE, NAME, plan.fingerprint());
        verify(client).putConfirmedAnprPlateRecord(anyString());
    }

    @Test
    void changedRecordRequiresANewConfirmationAndPreservesExistingListType() throws Exception {
        var client = mock(DigestHttpClient.class);
        var rows = new AtomicReference<>(row("1", "10-AB-123", "Previous", "blackList"));
        camera(client, rows);
        var before = AnprPlateTransfer.prepare(client, PLATE, NAME);
        assertThat(before.record().path("id").asText()).isEqualTo("1");
        assertThat(before.record().path("LicensePlate").asText()).isEqualTo("10-AB-123");
        assertThat(before.record().path("listType").asText()).isEqualTo("blockList");
        assertThat(before.record().path("cardNo").asText()).isEqualTo("card-fixture");
        rows.set(row("1", "10-AB-123", "Someone else", "blackList"));
        assertThatThrownBy(() -> AnprPlateTransfer.apply(client, PLATE, NAME, before.plan().fingerprint()))
                .isInstanceOf(IOException.class);
        verify(client, never()).putConfirmedAnprPlateRecord(anyString());
    }

    private void camera(DigestHttpClient client, AtomicReference<String> rows) throws Exception {
        var info = http("<DeviceInfo><serialNumber>fixture-camera</serialNumber></DeviceInfo>");
        var capabilities = http("""
                {"LicensePlateInfoCap":{
                  "id":{"@min":1,"@max":16},"name":{"@max":32},"licensePlate":{"@max":32},
                  "operationType":{"@opt":["add","modify"]},"listType":{"@opt":["allowList","blockList"]},
                  "plateColor":{"@opt":["unknown"]},"plateType":{"@opt":["unknown"]}
                }}
                """);
        when(client.get("/ISAPI/System/deviceInfo")).thenReturn(info);
        when(client.get(ANPR_PLATE_CAPABILITIES)).thenReturn(capabilities);
        when(client.post(eq(ANPR_PLATE_SEARCH), anyString(), anyString())).thenAnswer(call -> {
            var id = Pattern.compile("<searchID>([^<]+)</searchID>").matcher(call.<String>getArgument(2));
            if (!id.find()) throw new AssertionError("searchID missing");
            int count = rows.get().split("</LicensePlateInfo>", -1).length - 1;
            return http("""
                    <LPListAuditSearchResult><searchID>%s</searchID><responseStatus>true</responseStatus>
                      <responseStatusStrg>OK</responseStatusStrg><numOfMatches>%d</numOfMatches>
                      <totalMatches>%d</totalMatches><LicensePlateInfoList>%s</LicensePlateInfoList>
                    </LPListAuditSearchResult>
                    """.formatted(id.group(1), count, count, rows.get()));
        });
    }

    private String row(String id, String plate, String name, String list) {
        return """
                <LicensePlateInfo><id>%s</id><LicensePlate>%s</LicensePlate><type>%s</type><name>%s</name>
                  <plateColor>unknown</plateColor><plateCategory>unknown</plateCategory><cardNo>card-fixture</cardNo>
                </LicensePlateInfo>
                """.formatted(id, plate, list, name);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> http(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }
}
