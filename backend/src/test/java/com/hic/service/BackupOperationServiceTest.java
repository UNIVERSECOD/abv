package com.hic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.exception.BadRequestException;
import com.hic.repository.TenantRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupOperationServiceTest {
    @TempDir Path runtime;
    ObjectMapper json = new ObjectMapper();
    TenantRepository tenants = mock(TenantRepository.class);
    BackupOperationService service;

    @BeforeEach void setup() throws Exception {
        TenantContext.setTenantId(7L);
        when(tenants.count()).thenReturn(1L);
        Files.createDirectories(root());
        Files.writeString(root().resolve("heartbeat.json"), "{}");
        service = new BackupOperationService(runtime.toString(), json, tenants);
    }
    @AfterEach void cleanup() { TenantContext.clear(); }
    Path root() { return runtime.resolve("backup-operations"); }

    @Test void createQueuesOnlyFixedHostActionAndTenantOwner() throws Exception {
        var job = service.create(null);
        assertThat(job.state()).isEqualTo("QUEUED");
        assertThat(json.readTree(root().resolve(job.id()).resolve("request.json").toFile()).path("action").asText()).isEqualTo("CREATE");
        assertThat(json.readTree(root().resolve(job.id()).resolve("owner.json").toFile()).path("tenantId").asLong()).isEqualTo(7L);
    }

    @Test void wholeHostBackupCannotExposeAnotherTenant() {
        when(tenants.count()).thenReturn(2L);
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(BadRequestException.class);
    }

    @Test void missingWorkerFailsBeforeQueueCreation() throws Exception {
        Files.delete(root().resolve("heartbeat.json"));
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(BadRequestException.class).hasMessageContaining("köməkçisi");
    }

    @Test void ownershipAndPathTraversalAreRejected() {
        var job = service.create(null);
        TenantContext.setTenantId(8L);
        assertThatThrownBy(() -> service.read(job.id())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.read("../settings")).isInstanceOf(BadRequestException.class);
    }

    @Test void emptyUploadDoesNotQueueRestore() {
        assertThatThrownBy(() -> service.create(new ByteArrayInputStream(new byte[0]))).isInstanceOf(BadRequestException.class);
    }

    @Test void restoreNeedsSuccessfulInspectionAndExplicitConfirmation() throws Exception {
        var job = service.create(new ByteArrayInputStream(new byte[] {1}));
        assertThatThrownBy(() -> service.restore(job.id(), true)).isInstanceOf(BadRequestException.class);
        ready(job.id());
        assertThatThrownBy(() -> service.restore(job.id(), false)).isInstanceOf(BadRequestException.class);
        assertThat(Files.exists(root().resolve(job.id()).resolve("restore-request.json"))).isFalse();
    }

    @Test void statusCapabilitySurvivesLoginChangeButCannotExposePreviewOrFiles() throws Exception {
        var job = service.create(new ByteArrayInputStream(new byte[] {1}));
        ready(job.id());
        var restoring = service.restore(job.id(), true);
        assertThat(restoring.progressToken()).isNotBlank();
        TenantContext.clear();
        assertThat(service.progress(job.id(), restoring.progressToken()).preview()).isNull();
        assertThatThrownBy(() -> service.progress(job.id(), "wrong")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.download(job.id(), true)).isInstanceOf(BadRequestException.class);
    }

    @Test void concurrentRestoreIsRejected() throws Exception {
        var first = service.create(new ByteArrayInputStream(new byte[] {1})); ready(first.id());
        var second = service.create(new ByteArrayInputStream(new byte[] {1})); ready(second.id());
        service.restore(first.id(), true);
        assertThatThrownBy(() -> service.restore(second.id(), true)).hasMessageContaining("artıq");
    }

    @Test void archiveDownloadNeedsCompletedJob() throws Exception {
        var job = service.create(null);
        Files.write(root().resolve(job.id()).resolve("backup.zip"), new byte[] {1});
        assertThatThrownBy(() -> service.download(job.id(), false)).isInstanceOf(BadRequestException.class);
        json.writeValue(root().resolve(job.id()).resolve("status.json").toFile(), Map.of("state", "SUCCESS", "message", "ok"));
        assertThat(service.download(job.id(), false).getFileName().toString()).isEqualTo("backup.zip");
    }

    void ready(String id) throws Exception {
        json.writeValue(root().resolve(id).resolve("status.json").toFile(),
                Map.of("state", "READY", "message", "ready", "preview", Map.of("files", 4)));
    }
}
