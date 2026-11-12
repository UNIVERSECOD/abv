package com.abv.hrerpisapi.config;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;

/** Stored separately from database/configuration backups. Back up this file securely. */
public final class DeviceCredentialKey {
    private DeviceCredentialKey() {}

    public static String load(String filename) {
        Path path = Path.of(filename).toAbsolutePath();
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Path temporary = Files.createTempFile(path.getParent(), ".device-key-", ".tmp");
                try {
                    PosixFileAttributeView posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
                    if (posix != null) {
                        posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
                    } else {
                        AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
                        if (acl == null) throw new IllegalStateException("Cannot protect device key file");
                        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                                .setPrincipal(Files.getOwner(temporary)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                    }
                    byte[] bytes = new byte[32];
                    new SecureRandom().nextBytes(bytes);
                    Files.writeString(temporary, Base64.getEncoder().encodeToString(bytes));
                    // Create a complete, private key once. A concurrent initializer retains the winner.
                    try { Files.createLink(path, temporary); }
                    catch (FileAlreadyExistsException ignored) { }
                } finally { Files.deleteIfExists(temporary); }
            }
            String key = Files.readString(path).trim();
            if (Base64.getDecoder().decode(key).length != 32) throw new IllegalStateException("Invalid device key");
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Device credential key is unavailable. Restore the original key file.", e);
        }
    }
}
