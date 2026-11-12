package com.abv.hrerpisapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DeviceCredentialUpgrade implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final DeviceCredentialCipher cipher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.query("SELECT id, password FROM devices FOR UPDATE", rs -> {
            String stored = rs.getString("password");
            // Authenticate existing ciphertext before any device workers are started.
            String plaintext = cipher.convertToEntityAttribute(stored);
            if (stored != null && !stored.startsWith("ISAPIENC1:")) {
                jdbc.update("UPDATE devices SET password = ? WHERE id = ?",
                        cipher.convertToDatabaseColumn(plaintext), rs.getLong("id"));
            }
        });
    }
}
