package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.DeviceAddress;
import com.abv.hrerpisapi.device.client.AnprCapabilities;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/anpr/cameras/{deviceId}/capabilities")
public class AnprCapabilitiesController {
    private final DeviceRepository devices;
    private final AnprCapabilities capabilities;
    private final Set<Long> checking = ConcurrentHashMap.newKeySet();
    @Value("${isapi.api-key:}")
    private String apiKey;

    public record Target(String endpoint, String cameraName) {}

    @PostMapping
    public AnprCapabilities.Report check(@PathVariable long deviceId,
            @RequestHeader("X-Allowed-Device-Ids") String scope, @RequestBody Target target) {
        if (apiKey == null || apiKey.isBlank() || !Long.toString(deviceId).equals(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var device = devices.findById(deviceId).filter(row -> row.isAnpr())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!Objects.equals(device.getName(), target.cameraName())
                || !DeviceAddress.normalize(device.getIp()).equals(target.endpoint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (!checking.add(deviceId)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        try { return capabilities.check(device); }
        finally { checking.remove(deviceId); }
    }
}
