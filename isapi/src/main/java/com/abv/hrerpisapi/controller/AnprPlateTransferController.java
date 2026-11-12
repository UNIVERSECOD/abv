package com.abv.hrerpisapi.controller;

import com.abv.hrerpisapi.dao.entity.DeviceEntity;
import com.abv.hrerpisapi.dao.repository.DeviceRepository;
import com.abv.hrerpisapi.device.DeviceAddress;
import com.abv.hrerpisapi.device.client.AnprPlateTransfer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/anpr/cameras/{deviceId}/plate-transfer")
public class AnprPlateTransferController {
    private final DeviceRepository devices;
    private final AnprPlateTransfer transfer;
    private final Set<Long> writing = ConcurrentHashMap.newKeySet();

    @Value("${isapi.api-key:}")
    private String apiKey;

    public record Request(String endpoint, String cameraName, String plate, String fullName, String fingerprint,
                          boolean confirmed, boolean removal) {}

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@PathVariable long deviceId, @RequestHeader("X-Allowed-Device-Ids") String scope,
                                     @RequestBody Request request) {
        var camera = camera(deviceId, scope, request);
        try {
            return ResponseEntity.ok(request.removal()
                    ? transfer.previewRemoval(camera, request.plate(), request.fullName())
                    : transfer.preview(camera, request.plate(), request.fullName()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("message", "Kameranın yoxlanması dayandırıldı."));
        } catch (IOException ex) {
            return failure(ex);
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@PathVariable long deviceId, @RequestHeader("X-Allowed-Device-Ids") String scope,
                                   @RequestBody Request request) {
        if (!request.confirmed() || request.fingerprint() == null || !request.fingerprint().matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var camera = camera(deviceId, scope, request);
        if (!writing.add(deviceId)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        try {
            if (request.removal()) transfer.remove(camera, request.plate(), request.fullName(), request.fingerprint());
            else transfer.apply(camera, request.plate(), request.fullName(), request.fingerprint());
            return ResponseEntity.noContent().build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("message", "Göndərmənin nəticəsi məlum deyil. Kameranı yenidən yoxlayın."));
        } catch (IOException ex) {
            return failure(ex);
        } finally {
            writing.remove(deviceId);
        }
    }

    private DeviceEntity camera(long id, String scope, Request request) {
        if (apiKey == null || apiKey.isBlank() || !Long.toString(id).equals(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var device = devices.findById(id).filter(row -> row.isAnpr() && row.isEnabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!Objects.equals(device.getName(), request.cameraName())
                || !DeviceAddress.normalize(device.getIp()).equals(request.endpoint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return device;
    }

    private ResponseEntity<Map<String, String>> failure(IOException error) {
        String message = error.getMessage();
        // Transport/parser messages can include camera response content. Only return our domain messages.
        if (message == null || !message.startsWith("Kamera") && !message.startsWith("Əməkdaş")
                && !message.startsWith("Ad, soyad")) {
            message = "Kamera ilə əməliyyat tamamlanmadı. Kamera siyahısını yoxlayın.";
        }
        return ResponseEntity.status(502).body(Map.of("message", message));
    }
}
