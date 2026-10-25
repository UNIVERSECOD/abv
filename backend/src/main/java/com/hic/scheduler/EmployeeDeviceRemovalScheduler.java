package com.hic.scheduler;

import com.hic.repository.TenantRepository;
import com.hic.service.EmployeeDeviceRemovalService;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmployeeDeviceRemovalScheduler {
    @org.springframework.beans.factory.annotation.Value("${app.runtime-dir:runtime}")
    private String runtimeDirectory = "runtime";
    private final TenantRepository tenants;
    private final EmployeeDeviceRemovalService removals;

    @Scheduled(fixedDelayString = "${employee.removal.retry-ms:60000}", initialDelay = 60000,
            scheduler = "deviceRemovalScheduler")
    public void retryPending() {
        if (java.nio.file.Files.exists(java.nio.file.Path.of(runtimeDirectory, "device-removal-paused.json"))) return;
        for (var tenant : tenants.findAll()) {
            try {
                TenantContext.setTenantId(tenant.getId());
                removals.processDue(tenant.getId());
            } catch (RuntimeException ex) {
                log.warn("Device removal retry interrupted for tenant {}", tenant.getId());
            } finally {
                TenantContext.clear();
            }
        }
    }
}
