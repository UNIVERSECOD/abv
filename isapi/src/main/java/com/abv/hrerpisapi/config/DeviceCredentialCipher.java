package com.abv.hrerpisapi.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@Converter
public class DeviceCredentialCipher implements AttributeConverter<String, String> {
    private static final String PREFIX = "ISAPIENC1:";
    private final SecretKeySpec key;

    @org.springframework.beans.factory.annotation.Autowired
    public DeviceCredentialCipher(@Value("${device-credential-key-file:${APP_RUNTIME_DIR:runtime}/device-credential-key}") String filename) {
        this(DeviceCredentialKey.load(filename), true);
    }

    DeviceCredentialCipher(String secret, boolean supplied) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("ISAPI_ENCRYPTION_KEY must contain at least 32 random characters");
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize device encryption", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String value) {
        if (value == null) return null;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt device credential", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length < 28) throw new IllegalArgumentException("Invalid encrypted credential");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
            return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt device credential with the configured key", e);
        }
    }
}
