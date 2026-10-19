package com.hic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.exception.BadRequestException;
import com.hic.repository.TenantRepository;
import com.hic.util.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Files are exchanged with the host worker; the web process never controls Docker/services. */
@Service
public class BackupOperationService {
    public static final long MAX_UPLOAD = 4L * 1024 * 1024 * 1024;
    private final Path root;
    private final ObjectMapper json;
    private final TenantRepository tenants;

    public BackupOperationService(@Value("${app.runtime-dir:runtime}") String runtime,
                                  ObjectMapper json, TenantRepository tenants) {
        this.root = Path.of(runtime).toAbsolutePath().normalize().resolve("backup-operations");
        this.json = json;
        this.tenants = tenants;
    }

    public record Operation(String id, String state, String message, JsonNode preview, String progressToken) {}

    public synchronized Operation create(InputStream upload) {
        requireHostAdmin();
        requireWorker();
        try {
            String id = UUID.randomUUID().toString();
            Path folder = root.resolve(id);
            Files.createDirectories(folder);
            if (upload != null) {
                try (var output = Files.newOutputStream(folder.resolve("upload.zip"), StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    int count;
                    while ((count = upload.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_UPLOAD) throw new BadRequestException("Backup faylı 4 GB-dan böyükdür");
                        output.write(buffer, 0, count);
                    }
                    if (total == 0) throw new BadRequestException("Backup faylı boşdur");
                } catch (Exception ex) {
                    Files.deleteIfExists(folder.resolve("upload.zip"));
                    throw ex;
                }
            }
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("tenantId", TenantContext.getTenantId());
            metadata.put("createdAt", Instant.now().toString());
            metadata.put("kind", upload == null ? "CREATE" : "INSPECT");
            atomic(folder.resolve("owner.json"), metadata);
            atomic(folder.resolve("status.json"), Map.of("state", "QUEUED", "message", "Sorğu növbəyə alındı"));
            atomic(folder.resolve("request.json"), Map.of("action", upload == null ? "CREATE" : "INSPECT"));
            return read(id);
        } catch (BadRequestException ex) { throw ex; }
        catch (Exception ex) { throw new BadRequestException("Backup sorğusu hazırlana bilmədi"); }
    }

    public Operation read(String id) {
        requireOwner(id);
        return status(id, null);
    }

    public synchronized Operation restore(String id, boolean confirmed) {
        requireOwner(id);
        requireWorker();
        if (!confirmed) throw new BadRequestException("Bərpa təsdiq edilməlidir");
        Path folder = folder(id);
        try {
            if (!"READY".equals(status(id, null).state())) throw new BadRequestException("Əvvəl backup faylı yoxlanmalıdır");
            try {
                Files.writeString(root.resolve("restore-active.json"), id, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ex) {
                throw new BadRequestException("Başqa bərpa artıq davam edir");
            }
            String token = UUID.randomUUID() + "-" + UUID.randomUUID();
            atomic(folder.resolve("progress.json"), Map.of("hash", hash(token), "expiresAt", Instant.now().plusSeconds(86400).toString()));
            atomic(folder.resolve("status.json"), Map.of("state", "QUEUED", "message", "Bərpa növbəyə alındı"));
            atomic(folder.resolve("restore-request.json"), Map.of("action", "RESTORE"));
            return status(id, token);
        } catch (BadRequestException ex) { throw ex; }
        catch (Exception ex) { throw new BadRequestException("Bərpa sorğusu hazırlana bilmədi"); }
    }

    /** Opaque, expiring capability survives restoration of database users and JWT secrets. */
    public Operation progress(String id, String token) {
        try {
            JsonNode proof = json.readTree(folder(id).resolve("progress.json").toFile());
            if (token == null || token.length() > 100 || Instant.parse(proof.path("expiresAt").asText()).isBefore(Instant.now())
                    || !MessageDigest.isEqual(hash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    proof.path("hash").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException();
            }
            // Preview metadata is only available through the authenticated endpoint.
            Operation state = status(id, null);
            return new Operation(id, state.state(), state.message(), null, null);
        } catch (Exception ex) { throw new BadRequestException("Bərpa statusuna giriş etibarlı deyil"); }
    }

    public Path download(String id, boolean safety) {
        requireOwner(id);
        String state = status(id, null).state();
        if (!safety && !"SUCCESS".equals(state)) throw new BadRequestException("Backup hələ hazır deyil");
        Path file = folder(id).resolve(safety ? "before-restore.zip" : "backup.zip");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new BadRequestException("Backup faylı hazır deyil");
        return file;
    }

    private Operation status(String id, String token) {
        try {
            JsonNode data = json.readTree(folder(id).resolve("status.json").toFile());
            return new Operation(id, data.path("state").asText(), data.path("message").asText(), data.get("preview"), token);
        } catch (Exception ex) { throw new BadRequestException("Backup statusu oxuna bilmədi"); }
    }

    private void requireOwner(String id) {
        requireHostAdmin();
        try {
            JsonNode owner = json.readTree(folder(id).resolve("owner.json").toFile());
            if (owner.path("tenantId").asLong() != TenantContext.getTenantId()) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new BadRequestException("Backup sorğusu tapılmadı"); }
    }

    private void requireHostAdmin() {
        // A whole-installation dump must never expose another company's data to a tenant HR user.
        if (TenantContext.getTenantId() == null || tenants.count() != 1) {
            throw new BadRequestException("Tam sistem backupu yalnız bir şirkətli lokal quraşdırmada mümkündür");
        }
    }

    private void requireWorker() {
        try {
            if (Files.getLastModifiedTime(root.resolve("heartbeat.json")).toInstant().isBefore(Instant.now().minusSeconds(90))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ex) { throw new BadRequestException("Backup köməkçisi işləmir. Serverdə backup worker quraşdırılmalıdır"); }
    }

    private Path folder(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (Exception ex) { throw new BadRequestException("Backup sorğusu düzgün deyil"); }
        Path folder = root.resolve(id);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(folder)) throw new BadRequestException("Backup yolu düzgün deyil");
        return folder;
    }

    private void atomic(Path path, Object value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        json.writeValue(temporary.toFile(), value);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
