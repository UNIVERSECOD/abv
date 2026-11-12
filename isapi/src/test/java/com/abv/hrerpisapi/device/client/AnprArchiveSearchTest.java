package com.abv.hrerpisapi.device.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnprArchiveSearchTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-01T00:00:00+04:00");

    @Test
    void readsEveryPageWithOneSearchIdAndUsesVehiclePictureTrack() throws Exception {
        var requests = new ArrayList<String>();
        var rows = AnprArchiveSearch.read(START, START.plusDays(1), xml -> {
            requests.add(xml);
            String id = xml.substring(xml.indexOf("<searchID>") + 10, xml.indexOf("</searchID>"));
            return response(id, requests.size() == 1 ? "MORE" : "OK",
                    requests.size() == 1 ? "10-AB-123" : "99-ZZ-321");
        });
        assertThat(rows).extracting(AnprArchiveSearch.Capture::plate).containsExactly("10-AB-123", "99-ZZ-321");
        assertThat(requests.get(0)).contains("<trackID>103</trackID>", "<searchResultPostion>0</searchResultPostion>",
                "<contentType>metadata</contentType>", "2026-09-30T20:00:00Z");
        assertThat(requests.get(1)).contains("<searchResultPostion>1</searchResultPostion>");
    }

    @Test
    void consumesEachPageBeforeRequestingTheNextWithoutHoldingTheArchive() throws Exception {
        var saved = new ArrayList<String>();
        int found = AnprArchiveSearch.readPages(START, START.plusDays(1), xml -> {
            String id = xml.substring(xml.indexOf("<searchID>") + 10, xml.indexOf("</searchID>"));
            if (saved.isEmpty()) return response(id, "MORE", "10-AB-123");
            assertThat(saved).containsExactly("10-AB-123");
            return response(id, "OK", "99-ZZ-321");
        }, false, page -> page.forEach(row -> saved.add(row.plate())));
        assertThat(found).isEqualTo(2);
        assertThat(saved).containsExactly("10-AB-123", "99-ZZ-321");
    }

    @Test
    void processesTenThousandCapturesAsOneHundredBoundedPages() throws Exception {
        var position = new java.util.concurrent.atomic.AtomicInteger();
        int found = AnprArchiveSearch.readPages(START, START.plusDays(1), xml -> {
            String id = xml.substring(xml.indexOf("<searchID>") + 10, xml.indexOf("</searchID>"));
            int start = position.get();
            assertThat(xml).contains("<searchResultPostion>" + start + "</searchResultPostion>");
            String records = java.util.stream.IntStream.range(start, start + 100)
                    .mapToObj(i -> "<searchMatchItem><timeSpan><startTime>2026-10-01T04:30:00Z</startTime>"
                            + "</timeSpan><plateNo>DE" + i + "</plateNo></searchMatchItem>")
                    .collect(java.util.stream.Collectors.joining());
            return "<CMSearchResult><searchID>" + id + "</searchID><responseStatus>true</responseStatus>"
                    + "<responseStatusStrg>" + (start == 9900 ? "OK" : "MORE") + "</responseStatusStrg>"
                    + "<numOfMatches>100</numOfMatches><matchList>" + records + "</matchList></CMSearchResult>";
        }, false, page -> {
            assertThat(page).hasSize(100);
            assertThat(page.get(0).plate()).isEqualTo("DE" + position.get());
            position.addAndGet(page.size());
        });
        assertThat(found).isEqualTo(10_000);
        assertThat(position.get()).isEqualTo(found);
    }

    @Test
    void rejectsFailedResponseEvenWhenTheTextStatusLooksSuccessful() {
        UUID id = UUID.randomUUID();
        String failed = response(id.toString(), "OK", "10-AB-123")
                .replace("<responseStatusStrg>", "<responseStatus>false</responseStatus><responseStatusStrg>");
        assertThatThrownBy(() -> AnprArchiveSearch.parse(failed, id)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsRepeatedPageRatherThanReportingPartialSuccess() {
        assertThatThrownBy(() -> AnprArchiveSearch.read(START, START.plusDays(1), xml -> {
            String id = xml.substring(xml.indexOf("<searchID>") + 10, xml.indexOf("</searchID>"));
            return response(id, "MORE", "10-AB-123");
        })).isInstanceOf(IOException.class).hasMessageContaining("repeated");
    }

    @Test
    void readsDocumentedVehicleRecognitionVariant() throws Exception {
        UUID id = UUID.randomUUID();
        var page = AnprArchiveSearch.parse("""
                <VehicleRecognitionSearchResult xmlns="http://www.isapi.org/ver20/XMLSchema">
                 <searchID>%s</searchID><responseStatusStrg>OK</responseStatusStrg>
                 <VehicleModeList><VehicleMode><captureTime>2026-10-01T08:30:00+04:00</captureTime>
                 <licensePlate>10-AB-123</licensePlate></VehicleMode></VehicleModeList>
                </VehicleRecognitionSearchResult>
                """.formatted(id), id);
        assertThat(page.captures()).hasSize(1);
        assertThat(page.captures().get(0).plate()).isEqualTo("10-AB-123");
    }

    private String response(String id, String status, String plate) {
        return """
                <CMSearchResult xmlns="http://www.isapi.org/ver20/XMLSchema">
                <searchID>%s</searchID><responseStatusStrg>%s</responseStatusStrg>
                <matchList><searchMatchItem><timeSpan><startTime>2026-10-01T04:30:00Z</startTime></timeSpan>
                <plateNo>%s</plateNo></searchMatchItem></matchList>
                </CMSearchResult>
                """.formatted(id, status, plate);
    }
}
