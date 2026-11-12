package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.model.Employee;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.DeviceAddress;
import com.hic.util.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AnprPlateTransferService {
    private final EmployeeRepository employees;
    private final EmployeeVehicleService vehicles;
    private final DeviceConfigRepository cameras;
    private final RestTemplate bridge;
    private final String base;
    private final ConcurrentHashMap<Actor, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RemovalKey, PendingRemoval> pendingRemovals = new ConcurrentHashMap<>();

    public AnprPlateTransferService(EmployeeRepository employees, EmployeeVehicleService vehicles,
            DeviceConfigRepository cameras, @Qualifier("anprRestTemplate") RestTemplate bridge,
            @Value("${isapi.base-url}") String base) {
        this.employees = employees;
        this.vehicles = vehicles;
        this.cameras = cameras;
        this.bridge = bridge;
        this.base = base.replaceAll("/+$", "");
    }

    record Actor(long tenant, long user) {}
    record Target(long cameraId, long bridgeId, String endpoint, String cameraName, String role,
                  String plate, String fullName) {}
    record Plan(String operation, String listType, String fingerprint) {}
    record Payload(String endpoint, String cameraName, String plate, String fullName, String fingerprint,
                   boolean confirmed, boolean removal) {}
    record Pending(UUID token, long employee, long vehicle, Target target, Plan plan, Instant expiresAt) {}
    record BridgeError(String message) {}
    record RemovalKey(Actor actor, long camera) {}
    record RemovalPlan(String recordId, String listType, String fingerprint) {}
    record PendingRemoval(UUID token, long employee, long vehicle, Target target, String fingerprint, Instant expiresAt) {}
    public record RemovalPreview(UUID token, String endpoint, String cameraName, String plate, String recordId,
                                 String listType) {}

    public record Preview(UUID token, String endpoint, String cameraName, String role, String plate,
                          String fullName, String operation, String listType) {}

    public Preview preview(long tenant, long employeeId, long vehicleId, long cameraId) {
        Actor actor = actor(tenant);
        Target target = resolve(tenant, employeeId, vehicleId, cameraId);
        Plan plan = request(target, "preview", null, false, Plan.class);
        if (plan == null || plan.fingerprint() == null || !plan.fingerprint().matches("[0-9a-f]{64}")
                || plan.operation() == null || !List.of("add", "modify", "unchanged").contains(plan.operation())
                || plan.listType() == null || !List.of("allowList", "blockList").contains(plan.listType())) {
            throw new BadRequestException("Kameranın yazma əməliyyatı təsdiqlənmədi.");
        }
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (pending.size() >= 1024 && !pending.containsKey(actor)) {
            throw new BadRequestException("Kamera xidməti məşğuldur. Bir qədər sonra yenidən yoxlayın.");
        }
        UUID token = UUID.randomUUID();
        pending.put(actor, new Pending(token, employeeId, vehicleId, target, plan, now.plusSeconds(120)));
        return new Preview(token, target.endpoint(), target.cameraName(), target.role(), target.plate(),
                target.fullName(), plan.operation(), plan.listType());
    }

    @Transactional
    public void confirm(long tenant, long employeeId, long vehicleId, UUID token, boolean confirmed) {
        Actor actor = actor(tenant);
        vehicles.lockAssignments(tenant);
        var stored = pending.get(actor);
        if (!confirmed || stored == null || !stored.token().equals(token) || stored.employee() != employeeId
                || stored.vehicle() != vehicleId || !stored.expiresAt().isAfter(Instant.now())) {
            throw new BadRequestException("Kameranı yenidən yoxlayın və göndərməni təsdiqləyin.");
        }
        var target = resolve(tenant, employeeId, vehicleId, stored.target().cameraId());
        if (!target.equals(stored.target()) || !pending.remove(actor, stored)) {
            throw new BadRequestException("Əməkdaş, nömrə və ya kamera dəyişib. Yenidən yoxlayın.");
        }
        request(target, "apply", stored.plan().fingerprint(), false, Void.class);
    }

    public RemovalPreview previewRemoval(long tenant, long employeeId, long vehicleId, long cameraId) {
        var key = new RemovalKey(actor(tenant), cameraId);
        pendingRemovals.remove(key);
        var target = resolve(tenant, employeeId, vehicleId, cameraId, true);
        var plan = request(target, "preview", null, true, RemovalPlan.class);
        if (plan != null && plan.recordId() == null && plan.fingerprint() == null && plan.listType() == null) {
            return new RemovalPreview(null, target.endpoint(), target.cameraName(), target.plate(), null, null);
        }
        if (plan == null || plan.fingerprint() == null || !plan.fingerprint().matches("[0-9a-f]{64}")
                || plan.recordId() == null || plan.recordId().isBlank() || plan.recordId().length() > 16
                || !"allowList".equals(plan.listType())) {
            throw new BadRequestException("Kamera qeydi təsdiqlənmədi.");
        }
        Instant now = Instant.now();
        pendingRemovals.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (pendingRemovals.size() >= 1024 && !pendingRemovals.containsKey(key)) {
            throw new BadRequestException("Kamera xidməti məşğuldur. Bir qədər sonra yenidən yoxlayın.");
        }
        UUID token = UUID.randomUUID();
        pendingRemovals.put(key, new PendingRemoval(token, employeeId, vehicleId, target, plan.fingerprint(),
                now.plusSeconds(120)));
        return new RemovalPreview(token, target.endpoint(), target.cameraName(), target.plate(), plan.recordId(),
                plan.listType());
    }

    @Transactional
    public void confirmRemoval(long tenant, long employeeId, long vehicleId, long cameraId, UUID token,
                               boolean confirmed) {
        var key = new RemovalKey(actor(tenant), cameraId);
        vehicles.lockAssignments(tenant);
        var stored = pendingRemovals.get(key);
        if (!confirmed || stored == null || !stored.token().equals(token) || stored.employee() != employeeId
                || stored.vehicle() != vehicleId || !stored.expiresAt().isAfter(Instant.now())) {
            throw new BadRequestException("Kameranı yenidən yoxlayın və silinməni təsdiqləyin.");
        }
        var target = resolve(tenant, employeeId, vehicleId, cameraId, true);
        if (!target.equals(stored.target()) || !pendingRemovals.remove(key, stored)) {
            throw new BadRequestException("Əməkdaş, nömrə və ya kamera dəyişib. Yenidən yoxlayın.");
        }
        request(target, "apply", stored.fingerprint(), true, Void.class);
    }

    private Target resolve(long tenant, long employeeId, long vehicleId, long cameraId) {
        return resolve(tenant, employeeId, vehicleId, cameraId, false);
    }

    private Target resolve(long tenant, long employeeId, long vehicleId, long cameraId, boolean removal) {
        var employee = employees.findByTenantIdAndId(tenant, employeeId)
                .filter(row -> removal || row.getEmploymentStatus() != Employee.EmploymentStatus.TERMINATED)
                .orElseThrow(() -> new BadRequestException("Əməkdaş tapılmadı və ya ona xitam verilib."));
        var assignments = removal ? vehicles.retired(tenant, employeeId) : vehicles.list(tenant, employeeId);
        var vehicle = assignments.stream().filter(row -> row.id() == vehicleId).findFirst()
                .orElseThrow(() -> new BadRequestException("Nömrənin uyğun təyinatı tapılmadı."));
        var camera = cameras.findById(cameraId)
                .filter(row -> Objects.equals(row.getTenantId(), tenant) && row.isAnpr()
                        && "ACTIVE".equals(row.getStatus()) && row.getDeviceName() != null && !row.getDeviceName().isBlank())
                .orElseThrow(() -> new BadRequestException("Aktiv ANPR kamera seçin."));
        try {
            long bridgeId = Long.parseLong(camera.getDeviceId());
            if (bridgeId <= 0) throw new IllegalArgumentException();
            String name = (employee.getFirstName().strip() + " " + employee.getLastName().strip()).strip();
            String endpoint = camera.getDeviceIp();
            if (!endpoint.startsWith("[") && endpoint.indexOf(':') != endpoint.lastIndexOf(':')) {
                endpoint = "[" + endpoint + "]";
            }
            if (URI.create("http://" + endpoint).getPort() == -1 && camera.getDevicePort() != null) {
                endpoint += ":" + camera.getDevicePort();
            }
            return new Target(cameraId, bridgeId, DeviceAddress.normalize(endpoint), camera.getDeviceName(),
                    camera.getDoorRole(), vehicle.plate(), name);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Kamera bağlantısı etibarlı deyil.");
        }
    }

    private <T> T request(Target target, String action, String fingerprint, boolean removal, Class<T> type) {
        var headers = new HttpHeaders();
        headers.set("X-Allowed-Device-Ids", Long.toString(target.bridgeId()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        var payload = new Payload(target.endpoint(), target.cameraName(), target.plate(), target.fullName(),
                fingerprint, fingerprint != null, removal);
        try {
            return bridge.postForObject(base + "/api/anpr/cameras/" + target.bridgeId() + "/plate-transfer/" + action,
                    new HttpEntity<>(payload, headers), type);
        } catch (RestClientResponseException ex) {
            BridgeError error = null;
            try { error = ex.getResponseBodyAs(BridgeError.class); }
            catch (RuntimeException ignored) { /* Older bridge or non-JSON response. */ }
            if (error != null && error.message() != null && !error.message().isBlank()) {
                throw new BadRequestException(error.message());
            }
        } catch (RestClientException ignored) {
            // A timeout after the write may leave the record applied. Require a fresh preview before retrying.
        }
        throw new BadRequestException(fingerprint == null ? "Kameranı yoxlamaq alınmadı."
                : removal ? "Silinmənin nəticəsi məlum deyil. Kameranı yenidən yoxlayın."
                : "Göndərmənin nəticəsi məlum deyil. Kameranı yenidən yoxlayın.");
    }

    private static Actor actor(long tenant) {
        Long id = TenantContext.getUserId();
        if (id == null) throw new BadRequestException("İstifadəçi sessiyasını yeniləyin.");
        return new Actor(tenant, id);
    }
}
