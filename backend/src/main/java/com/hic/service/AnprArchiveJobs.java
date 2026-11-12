package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.util.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

@Service
@Slf4j
public class AnprArchiveJobs {
    private static final int MAX_RUNNING = 8;
    private static final int MAX_RETAINED = 128;
    private final AnprArchiveService archive;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "anpr-archive");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Job> jobs = new HashMap<>();

    public AnprArchiveJobs(AnprArchiveService archive) {
        this.archive = archive;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public record State(UUID id, String status, AnprArchiveService.Result result, String error) {}
    private record Scope(long tenant, long area, LocalDate from, LocalDate to) {}
    private record Job(Scope scope, State state, Instant finished) {}

    public synchronized State start(long tenant, long area, LocalDate from, LocalDate to) {
        return enqueue(new Scope(tenant, area, from, to), null);
    }

    public synchronized boolean startAutomatic(long tenant, long area, LocalDate from, LocalDate to,
                                               Consumer<Boolean> finished) {
        return enqueue(new Scope(tenant, area, from, to), finished) != null;
    }

    private State enqueue(Scope scope, Consumer<Boolean> finished) {
        boolean automatic = finished != null;
        long running = 0;
        for (Job job : jobs.values()) {
            if (job.finished() != null) continue;
            running++;
            if (job.scope().tenant() == scope.tenant() && job.scope().area() == scope.area()) {
                if (automatic) return null;
                if (job.scope().equals(scope)) return job.state();
                throw new BadRequestException("Bu ərazi üçün arxiv sorğusu artıq icra olunur.");
            }
        }
        if (running >= MAX_RUNNING) {
            if (automatic) return null;
            throw new BadRequestException("Arxiv xidməti məşğuldur. Bir qədər sonra təkrar edin.");
        }
        if (jobs.size() >= MAX_RETAINED) jobs.values().removeIf(job -> job.finished() != null);

        UUID id = UUID.randomUUID();
        State state = new State(id, "RUNNING", null, null);
        jobs.put(id, new Job(scope, state, null));
        Long userId = TenantContext.getUserId();
        String username = TenantContext.getUsername();
        try {
            executor.execute(() -> run(id, scope, userId, username, finished));
        } catch (RejectedExecutionException rejected) {
            jobs.remove(id);
            if (automatic) return null;
            throw new BadRequestException("Arxiv sorğusu başlatmaq mümkün olmadı.");
        }
        return state;
    }

    public synchronized State status(long tenant, UUID id) {
        Job job = jobs.get(id);
        if (job == null || job.scope().tenant() != tenant) throw new ResourceNotFoundException("Arxiv sorğusu");
        return job.state();
    }

    private void run(UUID id, Scope scope, Long userId, String username, Consumer<Boolean> finished) {
        TenantContext.setTenantId(scope.tenant());
        TenantContext.setUserId(userId);
        TenantContext.setUsername(username);
        State state;
        try {
            state = new State(id, "COMPLETE", archive.readAndApply(scope.tenant(), scope.area(), scope.from(), scope.to()), null);
        } catch (BadRequestException expected) {
            state = new State(id, "FAILED", null, expected.getMessage());
        } catch (RuntimeException unexpected) {
            log.warn("ANPR archive job {} failed with {}", id, unexpected.getClass().getSimpleName());
            state = new State(id, "FAILED", null, "Arxiv hesablaması tamamlanmadı. Sorğunu təkrarlayın.");
        } finally {
            TenantContext.clear();
        }
        synchronized (this) {
            jobs.put(id, new Job(scope, state, Instant.now()));
        }
        if (finished != null) {
            try {
                finished.accept("COMPLETE".equals(state.status()));
            } catch (RuntimeException ex) {
                log.warn("Could not advance ANPR archive cursor for tenant {} area {}: {}",
                        scope.tenant(), scope.area(), ex.getClass().getSimpleName());
            }
        }
    }
}
