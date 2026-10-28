package com.hic.service;

import com.hic.exception.UnauthorizedException;
import com.hic.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Bootstrap requires proof of access to the installation, not just its HTTP port. */
@Component
public class InitialSetupGuard {
    private final UserRepository users;
    private final Path tokenPath;
    private final String configuredToken;

    public InitialSetupGuard(UserRepository users,
            @Value("${app.runtime-dir:runtime}") String runtimeDirectory,
            @Value("${app.initial-setup-token:}") String configuredToken) {
        this.users = users;
        this.tokenPath = Path.of(runtimeDirectory).resolve("initial-setup-token");
        this.configuredToken = configuredToken;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepare() {
        if (users.count() == 0 || users.findByUsername("admin").map(u -> u.isPasswordSetupRequired()).orElse(false)) {
            expectedToken();
        }
    }

    public void verify(String supplied) {
        if (supplied == null || supplied.isBlank()
                || !MessageDigest.isEqual(expectedToken().getBytes(StandardCharsets.UTF_8),
                        supplied.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Quraşdırma kodu yanlışdır. Kodu sistem administratorundan alın");
        }
    }

    private synchronized String expectedToken() {
        if (configuredToken != null && !configuredToken.isBlank()) {
            if (configuredToken.length() < 32) throw new IllegalStateException("Initial setup token must have at least 32 characters");
            return configuredToken;
        }
        try {
            Files.createDirectories(tokenPath.getParent());
            if (!Files.exists(tokenPath)) {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                try {
                    try {
                        Files.createFile(tokenPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    } catch (UnsupportedOperationException unsupported) {
                        Files.createFile(tokenPath);
                    }
                    Files.writeString(tokenPath, HexFormat.of().formatHex(bytes), StandardCharsets.UTF_8);
                } catch (FileAlreadyExistsException concurrentCreator) {
                    // Another process prepared this installation's token.
                }
            }
            String token = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
            if (token.length() < 32) throw new IllegalStateException("Invalid initial setup token file");
            return token;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare initial setup token file", ex);
        }
    }
}
