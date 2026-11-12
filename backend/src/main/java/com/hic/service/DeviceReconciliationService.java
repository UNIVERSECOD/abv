package com.hic.service;

import com.hic.dto.DeviceReconciliationDTO.*;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Confirmation state is short-lived and process-local; restart requires a fresh preview.
 * Each request applies one step, so large devices do not exceed the HTTP proxy timeout.
 * Device writes never participate in an HR database transaction.
 */
@Service
@RequiredArgsConstructor
public class DeviceReconciliationService {
    private final DeviceConfigRepository devices;
    private final EmployeeRepository employees;
    private final EmployeeAreaRepository areas;
    private final EmployeeDeviceAccessRepository access;
    private final DeviceReconciliationClient client;
    private final AuditLogService audit;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    private record EmployeeState(Long id, String no, String name, Employee.EmploymentStatus status,
                                 LocalDate hireDate, String gender, boolean area, boolean manual) {}
    private record Source(Long bridgeId, String ip, String name, Long area, String status, List<EmployeeState> employees) {}
    private record State(DeviceConfig device, Source source, Map<Long, Employee> employees) {}
    private static class Session {
        Long tenant, user, device;
        Source source;
        Instant expires;
        List<DeviceReconciliationClient.Person> snapshot;
        List<Row> operations;
        List<Outcome> outcomes = new ArrayList<>();
        int next;
        boolean finished;
    }

    public Preview preview(Long id) {
        synchronized (lock(id)) {
            State state = state(id);
            List<DeviceReconciliationClient.Person> snapshot = verifiedSnapshot(state);
            List<Row> rows = plan(state.source(), snapshot);
            sessions.entrySet().removeIf(e -> e.getValue().expires.isBefore(Instant.now()));
            if (sessions.size() >= 1000) throw new BadRequestException("Çox sayda önbaxış açıqdır. Bir az sonra yenidən yoxlayın.");
            Session session = new Session();
            session.tenant = TenantContext.getTenantId(); session.user = TenantContext.getUserId(); session.device = id;
            session.source = state.source(); session.snapshot = snapshot; session.expires = Instant.now().plusSeconds(900);
            session.operations = rows.stream().filter(r -> r.action().equals("ADD") || r.action().equals("REMOVE"))
                    .sorted(Comparator.comparing(r -> r.action().equals("ADD") ? 0 : 1)).toList();
            String token = UUID.randomUUID().toString();
            sessions.put(token, session);
            return new Preview(token, session.expires, state.source().name(), state.source().ip(), state.source().area(),
                    rows, session.operations.size());
        }
    }

    public Progress apply(Long id, ApplyRequest request) {
        synchronized (lock(id)) {
            if (request == null || request.token() == null || !request.confirmed())
                throw new BadRequestException("Dəyişiklikləri təsdiqləyin.");
            Session s = sessions.get(request.token());
            if (s == null || !Objects.equals(s.tenant, TenantContext.getTenantId())
                    || !Objects.equals(s.user, TenantContext.getUserId()) || !Objects.equals(s.device, id))
                throw new BadRequestException("Önbaxış etibarlı deyil. Siyahını yeniləyin.");
            if (s.expires.isBefore(Instant.now())) {
                sessions.remove(request.token());
                throw new BadRequestException("Önbaxışın vaxtı bitib. Siyahını yeniləyin.");
            }
            // Retrying a lost HTTP response must not repeat a physical write.
            if (request.step() >= 0 && request.step() < s.next) return progress(s);
            if (s.finished) return progress(s);
            if (request.step() != s.next) throw new BadRequestException("Əməliyyat ardıcıllığı dəyişib. Siyahını yeniləyin.");
            State current = state(id);
            if (!s.source.equals(current.source()) || !s.snapshot.equals(verifiedSnapshot(current))) {
                sessions.remove(request.token());
                throw new BadRequestException("Ərazi, təyinat və ya cihazdakı siyahı dəyişib. Yenidən önbaxış edin.");
            }
            if (s.next == s.operations.size()) { s.finished = true; return progress(s); }
            if (!s.source.equals(state(id).source())) {
                sessions.remove(request.token());
                throw new BadRequestException("Təyinatlar dəyişib. Yenidən önbaxış edin.");
            }
            Row row = s.operations.get(s.next);
            long bridgeId = s.source.bridgeId();
            audit.log("UPDATE", "DeviceConfig", id.toString(), "Əməkdaş uyğunlaşdırılması təsdiqləndi: "
                    + row.action() + ", əməkdaş ID=" + row.employeeId());
            String status = "SUCCESS", message = "Cihazdan silindi";
            try {
                Long userId = null;
                if (row.action().equals("ADD")) userId = client.add(bridgeId, current.employees().get(row.employeeId()));
                else client.remove(bridgeId, row.employeeNo());
                List<DeviceReconciliationClient.Person> after = verifiedSnapshot(state(id));
                boolean present = after.stream().anyMatch(p -> p.employeeNo().equals(row.employeeNo())
                        && normalized(p.name()).equals(normalized(row.name())));
                boolean absent = after.stream().noneMatch(p -> p.employeeNo().equals(row.employeeNo()));
                if (!(row.action().equals("ADD") ? present : absent)) throw new IllegalStateException("Write not verified");
                // Concurrent changes outside this operation invalidate all remaining steps.
                if (!without(s.snapshot, row.employeeNo()).equals(without(after, row.employeeNo())))
                    throw new IllegalStateException("Concurrent device change");
                s.snapshot = after;
                if (row.action().equals("ADD")) {
                    message = "Əlavə olundu";
                    try {
                        if (!s.source.equals(state(id).source())) throw new IllegalStateException("Assignment changed");
                        message += ". " + client.addFace(bridgeId, userId, current.employees().get(row.employeeId()));
                    } catch (Exception ex) {
                        status = "WARNING"; message = "Əlavə olundu, şəkil göndərilmədi. Əməkdaş sinxronu ilə yenidən göndərin.";
                        s.finished = true;
                    }
                }
            } catch (RuntimeException ex) {
                status = "UNVERIFIED";
                message = "Nəticə təsdiqlənmədi. Qalan əməliyyatlar dayandırıldı; yenidən önbaxış edin.";
                s.finished = true;
            }
            s.outcomes.add(new Outcome(row, status, message));
            s.next++;
            s.finished |= s.next == s.operations.size();
            audit.log("UPDATE", "DeviceConfig", id.toString(), "Əməkdaş uyğunlaşdırılması: " + row.action()
                    + ", əməkdaş ID=" + row.employeeId() + ", nəticə=" + status);
            return progress(s);
        }
    }

    private Object lock(Long id) { return locks[Math.floorMod(Objects.hashCode(id), locks.length)]; }
    private Progress progress(Session s) { return new Progress(s.next, s.finished, List.copyOf(s.outcomes)); }
    private List<DeviceReconciliationClient.Person> without(List<DeviceReconciliationClient.Person> list, String no) {
        return list.stream().filter(p -> !p.employeeNo().equals(no)).toList();
    }
    private State state(Long id) {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null || TenantContext.getUserId() == null) throw new BadRequestException("İstifadəçi sessiyası etibarlı deyil.");
        DeviceConfig device = devices.findById(id).filter(d -> tenant.equals(d.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("DeviceConfig", id));
        if (device.isAnpr()) throw new BadRequestException("ANPR kamera əməkdaş sinxronunu dəstəkləmir.");
        Long bridge;
        try { bridge = Long.valueOf(device.getDeviceId()); if (bridge <= 0) throw new NumberFormatException(); }
        catch (RuntimeException ex) { throw new BadRequestException("Cihazın bağlantı identifikatoru etibarlı deyil."); }
        Set<Long> areaIds = device.getBranchId() == null ? Set.of() : areas.findByBranchId(device.getBranchId()).stream()
                .filter(a -> tenant.equals(a.getTenantId())).map(EmployeeArea::getEmployeeId).collect(Collectors.toSet());
        Set<Long> manualIds = access.findByDeviceConfigId(id).stream().filter(a -> tenant.equals(a.getTenantId()))
                .filter(a -> a.getAssignmentSource() == EmployeeDeviceAccess.AssignmentSource.MANUAL)
                .map(EmployeeDeviceAccess::getEmployeeId).collect(Collectors.toSet());
        List<Employee> all = employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenant);
        List<EmployeeState> source = all.stream().filter(e -> tenant.equals(e.getTenantId())).sorted(Comparator.comparing(Employee::getId))
                .map(e -> new EmployeeState(e.getId(), number(e), name(e), e.getEmploymentStatus(), e.getHireDate(),
                        e.getGender(), areaIds.contains(e.getId()), manualIds.contains(e.getId()))).toList();
        return new State(device, new Source(bridge, device.getDeviceIp(), device.getDeviceName(), device.getBranchId(),
                device.getStatus(), source), all.stream().collect(Collectors.toMap(Employee::getId, e -> e)));
    }
    private List<DeviceReconciliationClient.Person> verifiedSnapshot(State state) {
        try {
            Source s = state.source();
            var actual = client.device(s.bridgeId());
            var status = client.status(s.bridgeId());
            if (!"ACTIVE".equals(s.status()) || actual == null || !actual.isEnabled()
                    || !Objects.equals(s.bridgeId(), actual.getId()) || !Objects.equals(s.ip(), actual.getIp())
                    || !Objects.equals(s.name(), actual.getName()) || status == null || !status.isOnline()
                    || !Objects.equals(s.bridgeId(), status.getId())) throw new IllegalStateException("Device mismatch/offline");
            return client.snapshot(s.bridgeId());
        } catch (RuntimeException ex) {
            throw new BadRequestException("Cihazın kimliyi, onlayn vəziyyəti və tam əməkdaş siyahısı təsdiqlənmədi. Bağlantını yoxlayıb yenidən cəhd edin.");
        }
    }
    private List<Row> plan(Source source, List<DeviceReconciliationClient.Person> snapshot) {
        Map<String, List<EmployeeState>> byNo = source.employees().stream().collect(Collectors.groupingBy(EmployeeState::no));
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (var p : snapshot) {
            seen.add(p.employeeNo());
            List<EmployeeState> matches = byNo.getOrDefault(p.employeeNo(), List.of());
            if (matches.isEmpty()) { rows.add(new Row(null, p.employeeNo(), p.name(), "UNKNOWN", "Proqramda tanınmır; saxlanılacaq")); continue; }
            EmployeeState e = matches.get(0);
            if (matches.size() != 1 || !normalized(e.name()).equals(normalized(p.name())) || !"normal".equalsIgnoreCase(p.userType())) {
                rows.add(new Row(null, p.employeeNo(), p.name(), "CONFLICT", "Kimlik və ya istifadəçi növü uyğun deyil; saxlanılacaq")); continue;
            }
            boolean keep = (e.area() || e.manual()) && e.status() != Employee.EmploymentStatus.TERMINATED;
            rows.add(new Row(e.id(), e.no(), e.name(), keep ? "KEEP" : "REMOVE", keep
                    ? e.manual() ? "Fərdi təyinat" : "Ərazi üzvlüyü"
                    : e.status() == Employee.EmploymentStatus.TERMINATED ? "İşdən çıxarılıb" : "Cari əraziyə və cihaza təyin edilməyib"));
        }
        for (EmployeeState e : source.employees()) {
            if (seen.contains(e.no()) || !(e.area() || e.manual()) || e.status() == Employee.EmploymentStatus.TERMINATED) continue;
            boolean conflict = e.no().isBlank() || byNo.get(e.no()).size() != 1 || e.name().isBlank();
            String action = conflict ? "CONFLICT" : e.status() == Employee.EmploymentStatus.ACTIVE ? "ADD" : "SKIP";
            rows.add(new Row(e.id(), e.no(), e.name(), action, conflict ? "Terminal nömrəsi və ya ad qeyri-müəyyəndir"
                    : action.equals("SKIP") ? "Yalnız aktiv əməkdaşlar əlavə olunur" : e.manual() ? "Fərdi təyinat" : "Ərazi üzvlüyü"));
        }
        return List.copyOf(rows);
    }
    static String number(Employee e) { return e.getDeviceEmployeeNo() != null && !e.getDeviceEmployeeNo().isBlank()
            ? e.getDeviceEmployeeNo().trim() : Objects.toString(e.getEmployeeId(), "").trim(); }
    static String name(Employee e) { return (Objects.toString(e.getFirstName(), "") + " " + Objects.toString(e.getLastName(), "")).trim(); }
    private static String normalized(String name) { return Objects.toString(name, "").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
