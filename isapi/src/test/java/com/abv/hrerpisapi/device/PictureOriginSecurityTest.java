package com.abv.hrerpisapi.device;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PictureOriginSecurityTest {
    @Test
    void originMustMatchSchemeHostAndPortWithoutUserInfo() {
        assertThatCode(() -> DigestHttpClient.requireSameOrigin("http://192.0.2.10:80/p@WEBtoken", "http://192.0.2.10"))
                .doesNotThrowAnyException();
        for (String url : java.util.List.of("http://127.0.0.1/p", "http://192.0.2.10:8080/p", "https://192.0.2.10/p", "http://user@192.0.2.10/p", "file:///etc/passwd")) {
            assertThatThrownBy(() -> DigestHttpClient.requireSameOrigin(url, "http://192.0.2.10"))
                    .isInstanceOf(java.io.IOException.class);
        }
    }
}
