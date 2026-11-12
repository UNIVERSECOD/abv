package com.abv.hrerpisapi.service;

import com.abv.hrerpisapi.device.mapper.AnprEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AnprEventStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AnprEventStore store = new AnprEventStore(jdbc, new AnprEventMapper());

    @Test
    void retriesHaveSameKeyButLaterPassageWithSameUuidDoesNot() throws Exception {
        String xml = """
                <EventNotificationAlert>
                  <dateTime>2026-11-10T08:50:00+04:00</dateTime>
                  <channelID>1</channelID>
                  <eventType>ANPR</eventType>
                  <ANPR><licensePlate>10-AB-123</licensePlate><confidenceLevel>96</confidenceLevel></ANPR>
                  <UUID>vehicle-capture</UUID>
                </EventNotificationAlert>
                """;
        assertThat(store.capture(11L, xml)).isTrue();
        assertThat(store.capture(11L, xml.replace("08:50:00+04:00", "04:50:00Z"))).isTrue();
        assertThat(store.capture(11L, xml.replace("08:50", "18:12"))).isTrue();

        var keys = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).update(anyString(), eq(11L), keys.capture(), any(OffsetDateTime.class),
                eq("10-AB-123"), eq(96), eq("1"), anyString());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(2));
    }

    @Test
    void nonAnprXmlDoesNotTouchStorage() throws Exception {
        assertThat(store.capture(11L, "<EventNotificationAlert><eventType>VMD</eventType></EventNotificationAlert>"))
                .isFalse();
        verifyNoInteractions(jdbc);
    }
}
