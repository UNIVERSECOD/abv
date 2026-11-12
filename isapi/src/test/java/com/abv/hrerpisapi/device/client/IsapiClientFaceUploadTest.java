package com.abv.hrerpisapi.device.client;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.device.DigestHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IsapiClientFaceUploadTest {
    @Test
    void multipartUploadRetainsOriginalPictureAndEmployeeIdentity() throws Exception {
        DeviceEntity device = new DeviceEntity();
        device.setIp("device.invalid");
        device.setUsername("test");
        device.setPassword("test");
        String employeeNo = "person-\"42\\test";
        byte[] photo = {(byte) 0xff, (byte) 0xd8, 0, 17, (byte) 0xff, (byte) 0xd9};
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"statusCode\":1,\"statusString\":\"OK\"}");

        try (MockedConstruction<DigestHttpClient> clients = mockConstruction(DigestHttpClient.class,
                (client, context) -> when(client.putBytes(anyString(), anyString(), any(byte[].class)))
                        .thenReturn(response))) {
            IsapiClient client = new IsapiClient();
            org.springframework.test.util.ReflectionTestUtils.setField(client, "targetPolicy",
                    org.mockito.Mockito.mock(com.abv.hrerpisapi.device.DeviceTargetPolicy.class));
            assertThat(client.uploadFaceToFDLib(device, employeeNo, photo).success()).isTrue();
            ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
            verify(clients.constructed().getFirst()).putBytes(
                    eq("/ISAPI/Intelligent/FDLib/FDSetUp?format=json"), contentType.capture(), body.capture());
            String multipart = new String(body.getValue(), StandardCharsets.ISO_8859_1);
            int jsonStart = multipart.indexOf("\r\n\r\n") + 4;
            int jsonEnd = multipart.indexOf("\r\n--", jsonStart);
            JsonNode metadata = new ObjectMapper().readTree(multipart.substring(jsonStart, jsonEnd));
            assertThat(metadata.path("saveFacePic").isBoolean()).isTrue();
            assertThat(metadata.path("saveFacePic").asBoolean()).isTrue();
            assertThat(metadata.path("FPID").asText()).isEqualTo(employeeNo);
            assertThat(metadata.path("FDID").asText()).isEqualTo("1");
            assertThat(metadata.path("faceLibType").asText()).isEqualTo("blackFD");
            int imageHeader = multipart.indexOf("name=\"FaceImage\"");
            int imageStart = multipart.indexOf("\r\n\r\n", imageHeader) + 4;
            assertThat(Arrays.copyOfRange(body.getValue(), imageStart, imageStart + photo.length)).isEqualTo(photo);
            String boundary = contentType.getValue().split("boundary=", 2)[1];
            assertThat(multipart).startsWith("--" + boundary + "\r\n").endsWith("\r\n--" + boundary + "--\r\n");
        }
    }
}
