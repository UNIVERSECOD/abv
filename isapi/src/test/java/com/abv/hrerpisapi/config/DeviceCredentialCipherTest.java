package com.abv.hrerpisapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.*;

class DeviceCredentialCipherTest {
    @TempDir Path directory;
    @Test void preservesLegacyCredentialsAndUsesRandomAuthenticatedCiphertext() {
        var cipher = new DeviceCredentialCipher("a".repeat(32), true);
        assertThat(cipher.convertToEntityAttribute("old-password")).isEqualTo("old-password");
        String encrypted = cipher.convertToDatabaseColumn("old-password");
        assertThat(encrypted).startsWith("ISAPIENC1:").doesNotContain("old-password");
        assertThat(cipher.convertToDatabaseColumn("old-password")).isNotEqualTo(encrypted);
        assertThat(cipher.convertToEntityAttribute(encrypted)).isEqualTo("old-password");
        assertThatThrownBy(() -> new DeviceCredentialCipher("b".repeat(32), true).convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.convertToEntityAttribute("ISAPIENC1:broken"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void keySurvivesRestartAndCanDecryptRestoredDatabase() throws Exception {
        Path keyFile = directory.resolve("device-key");
        var first = new DeviceCredentialCipher(keyFile.toString());
        String encrypted = first.convertToDatabaseColumn("test-only");
        byte[] original = Files.readAllBytes(keyFile);
        var restarted = new DeviceCredentialCipher(keyFile.toString());
        assertThat(restarted.convertToEntityAttribute(encrypted)).isEqualTo("test-only");
        assertThat(Files.readAllBytes(keyFile)).isEqualTo(original);
    }
}
