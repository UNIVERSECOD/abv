package com.hic.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class AnprBridgeClient {
    private static final int PAGE_SIZE = 500;
    private static final int MAX_CAPTURES = 200_000;
    private final RestTemplate archiveClient;
    private final RestTemplate eventsClient;

    public AnprBridgeClient(@Qualifier("anprRestTemplate") RestTemplate archiveClient,
                           @Qualifier("anprEventsRestTemplate") RestTemplate eventsClient) {
        this.archiveClient = archiveClient;
        this.eventsClient = eventsClient;
    }

    @Value("${isapi.base-url}")
    private String baseUrl;

    public record Capture(long id, OffsetDateTime capturedAt, String plate, Integer confidence, String channel,
                          boolean archived) {
        public Capture(long id, OffsetDateTime capturedAt, String plate, Integer confidence, String channel) {
            this(id, capturedAt, plate, confidence, channel, false);
        }
    }

    public record ArchiveResult(int found, int added) {}

    public ArchiveResult fetchArchive(long deviceId, OffsetDateTime start, OffsetDateTime end) {
        var uri = UriComponentsBuilder.fromHttpUrl(baseUrl).pathSegment("api", "anpr", "archive")
                .queryParam("deviceId", deviceId).queryParam("start", "{start}").queryParam("end", "{end}")
                .encode().buildAndExpand(start.toString(), end.toString()).toUri();
        ArchiveResult result = archiveClient.exchange(uri, HttpMethod.POST, scoped(deviceId), ArchiveResult.class).getBody();
        if (result == null || result.found() < 0 || result.added() < 0 || result.added() > result.found()) {
            throw new IllegalStateException("Invalid ANPR archive result");
        }
        return result;
    }

    public List<Capture> captures(long deviceId, OffsetDateTime start, OffsetDateTime end) {
        var scope = scoped(deviceId);
        List<Capture> captures = new ArrayList<>();
        long after = 0;
        while (true) {
            var uri = UriComponentsBuilder.fromHttpUrl(baseUrl).pathSegment("api", "anpr", "events")
                    .queryParam("deviceId", deviceId).queryParam("afterId", after)
                    .queryParam("limit", PAGE_SIZE).queryParam("start", "{start}").queryParam("end", "{end}")
                    .encode().buildAndExpand(start.toString(), end.toString()).toUri();
            var batch = eventsClient.exchange(uri, HttpMethod.GET, scope, Capture[].class).getBody();
            if (batch == null || batch.length > PAGE_SIZE) throw new IllegalStateException("Invalid ANPR capture page");
            if (batch.length == 0) return captures;
            if (captures.size() + batch.length > MAX_CAPTURES) {
                throw new IllegalStateException("ANPR capture range exceeds processing limit");
            }
            for (Capture capture : batch) {
                if (capture == null || capture.id() <= after || capture.capturedAt() == null
                        || capture.capturedAt().isBefore(start) || capture.capturedAt().isAfter(end)) {
                    throw new IllegalStateException("ANPR bridge returned invalid or unordered captures");
                }
                after = capture.id();
                captures.add(capture);
            }
            if (batch.length < PAGE_SIZE) return captures;
        }
    }

    private static HttpEntity<Void> scoped(long deviceId) {
        var headers = new HttpHeaders();
        headers.set("X-Allowed-Device-Ids", Long.toString(deviceId));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }
}
