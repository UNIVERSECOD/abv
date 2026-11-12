package com.abv.hrerpisapi.device.mapper;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnprEventMapperTest {
    private final AnprEventMapper mapper = new AnprEventMapper();

    private static final String CAPTURE = """
            <EventNotificationAlert xmlns="http://www.isapi.org/ver20/XMLSchema">
              <channelID>1</channelID>
              <dateTime>2026-11-10T08:50:00+04:00</dateTime>
              <eventType>ANPR</eventType>
              <eventState>active</eventState>
              <ANPR>
                <licensePlate>10-AB-123</licensePlate>
                <confidenceLevel>96</confidenceLevel>
                <line>1</line>
                <extraPlate><licensePlate>11-CD-456</licensePlate></extraPlate>
              </ANPR>
              <UUID>capture-123</UUID>
            </EventNotificationAlert>
            """;

    @Test
    void mapsPrimaryCaptureWithTimestampAndConfidence() throws Exception {
        var event = mapper.map(CAPTURE);
        assertThat(event.plate()).isEqualTo("10-AB-123");
        assertThat(event.capturedAt()).isEqualTo(OffsetDateTime.parse("2026-11-10T08:50:00+04:00"));
        assertThat(event.confidence()).isEqualTo(96);
        assertThat(event.channel()).isEqualTo("1");
        assertThat(event.uuid()).isEqualTo("capture-123");
    }

    @Test
    void ignoresInactiveAndAccessControlMessages() throws Exception {
        assertThat(mapper.map(CAPTURE.replace(">active<", ">inactive<"))).isNull();
        assertThat(mapper.map(CAPTURE.replace(">ANPR<", ">AccessControllerEvent<"))).isNull();
    }

    @Test
    void rejectsDtds() {
        assertThatThrownBy(() -> mapper.map("<!DOCTYPE EventNotificationAlert>" + CAPTURE))
                .isInstanceOf(XMLStreamException.class);
    }
}
