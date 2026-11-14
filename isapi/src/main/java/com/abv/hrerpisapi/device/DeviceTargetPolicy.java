package com.abv.hrerpisapi.device;

import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DeviceTargetPolicy {
    private final DeviceRepository devices;
    private final Set<String> configured;
    private final Set<IpNetwork> configuredNetworks;
    private final boolean legacyRegistrationAllowed;

    public DeviceTargetPolicy(DeviceRepository devices, @Value("${isapi.allowed-targets:}") String allowed,
            @Value("${isapi.allowed-networks:}") String networks) {
        this.devices = devices;
        legacyRegistrationAllowed = allowed.isBlank() && networks.isBlank();
        configured = Arrays.stream(allowed.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(DeviceAddress::normalize).collect(Collectors.toUnmodifiableSet());
        configuredNetworks = Arrays.stream(networks.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(IpNetwork::parse).collect(Collectors.toUnmodifiableSet());
    }

    public void requireAllowed(String address) {
        String normalized;
        try { normalized = DeviceAddress.normalize(address); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        if (configured.contains(normalized)
                || configuredNetworks.stream().anyMatch(network -> network.containsEndpoint(normalized))) return;
        // Preserve existing installations. New endpoints require host configuration.
        boolean registered = legacyRegistrationAllowed && devices.findAll().stream()
                .anyMatch(device -> matches(normalized, device.getIp()));
        if (!registered) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bu IP və port üçün cihaz bağlantısına icazə verilməyib");
        }
    }

    private boolean matches(String normalized, String candidate) {
        try { return normalized.equals(DeviceAddress.normalize(candidate)); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
