package com.hic.service;

import com.hic.repository.DeviceConfigRepository;
import com.hic.util.TenantContext;
import com.hic.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class DeviceTargetPolicy {
    private final DeviceConfigRepository devices;
    @Value("${devices.allowed-targets:}")
    private String allowedTargets = "";
    @Value("${devices.allowed-networks:}")
    private String allowedNetworks = "";

    public void requireAllowed(String endpoint, Long branchId) {
        String normalized;
        try {
            normalized = com.hic.util.DeviceAddress.normalize(endpoint);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BadRequestException("Şirkət müəyyən edilmədi");
        String scope = tenantId + "/" + (branchId == null ? "none" : branchId);
        for (String rule : allowedTargets.split(";")) {
            String[] parts = rule.trim().split("=", 2);
            if (parts.length == 2 && scope.equals(parts[0].trim())
                    && matchesConfiguredTarget(normalized, parts[1].trim())) return;
        }
        for (String rule : allowedNetworks.split(";")) {
            String[] parts = rule.trim().split("=", 2);
            if (parts.length == 2 && scope.equals(parts[0].trim())
                    && matchesNetwork(normalized, parts[1].trim())) return;
        }
        boolean existing = allowedTargets.isBlank() && allowedNetworks.isBlank()
                && devices.findByTenantId(tenantId).stream()
                .filter(device -> Objects.equals(branchId, device.getBranchId()))
                .anyMatch(device -> matches(normalized, device.getDeviceIp(), device.getDevicePort()));
        if (!existing) throw new BadRequestException("Bu IP və port seçilmiş ərazi üçün icazəli deyil. Quraşdırmanın cihaz ünvanı qaydasını yoxlayın.");
    }

    private boolean matchesConfiguredTarget(String normalized, String candidate) {
        try { return normalized.equals(com.hic.util.DeviceAddress.normalize(candidate)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private boolean matchesNetwork(String normalized, String cidr) {
        try { return com.hic.util.IpNetwork.parse(cidr).containsEndpoint(normalized); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private boolean matches(String normalized, String candidate, Integer port) {
        try { return normalized.equals(com.hic.util.DeviceAddress.normalize(candidate, port)); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
