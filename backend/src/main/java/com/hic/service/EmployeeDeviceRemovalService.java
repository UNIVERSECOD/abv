package com.hic.service;

import com.hic.dto.EmployeeTerminationResultDTO;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import java.util.*;

/** Durable outbox: termination and its jobs commit before any terminal request. */
@Service
@RequiredArgsConstructor
public class EmployeeDeviceRemovalService {
    private final EmployeeRepository employees;
    private final DeviceConfigRepository devices;
    private final EmployeeDeviceRemovalJobRepository jobs;
    private final EmployeeRemovalDeviceClient client;
    private final TransactionTemplate transaction;

    public EmployeeTerminationResultDTO terminate(Employee employee, Set<Long> targetIds) {
        Long tenantId = Objects.requireNonNull(employee.getTenantId(), "Əməkdaşın şirkəti tapılmadı");
        boolean newlyTerminated = employee.getEmploymentStatus() != Employee.EmploymentStatus.TERMINATED;
        transaction.executeWithoutResult(tx -> {
            // Serializes simultaneous requests so the same employee/device job is never duplicated.
            Employee locked = employees.lockForTermination(tenantId, employee.getId()).orElseThrow();
            Set<Long> existing = new HashSet<>();
            jobs.findByTenantIdAndEmployeeIdOrderById(tenantId, employee.getId())
                    .forEach(job -> existing.add(job.getDeviceConfigId()));
            for (Long targetId : targetIds) {
                if (existing.contains(targetId)) continue;
                DeviceConfig device = devices.findById(targetId)
                        .filter(d -> tenantId.equals(d.getTenantId())).orElse(null);
                EmployeeDeviceRemovalJob job = new EmployeeDeviceRemovalJob();
                job.setTenantId(tenantId);
                job.setEmployeeId(employee.getId());
                job.setDeviceConfigId(targetId);
                job.setEmployeeNo(personNo(locked));
                if (device != null) {
                    job.setBridgeDeviceId(device.getDeviceId());
                    job.setDeviceIp(device.getDeviceIp());
                    job.setDeviceName(device.getDeviceName());
                }
                job.setNextAttemptAt(AppTimeZone.now());
                jobs.save(job);
            }
            locked.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);
            if (newlyTerminated && locked.getTerminationDate() == null) {
                locked.setTerminationDate(employee.getTerminationDate() != null
                        ? employee.getTerminationDate()
                        : AppTimeZone.today());
            }
            employees.saveAndFlush(locked);
        });
        for (var job : jobs.findByTenantIdAndEmployeeIdOrderById(tenantId, employee.getId())) {
            if (!job.isCompleted()) attempt(tenantId, job.getId(), true);
        }
        return result(tenantId, employee.getId());
    }

    public void processDue(Long tenantId) {
        for (Long id : jobs.findDueIds(tenantId, AppTimeZone.now(), PageRequest.of(0, 25))) {
            attempt(tenantId, id, false);
        }
    }

    public void attempt(Long tenantId, Long id, boolean manual) {
        // Lock retained during the bounded HTTP calls: a second worker cannot delete concurrently.
        // A crash rolls back the attempt; the idempotent bridge delete is retried after restart.
        transaction.executeWithoutResult(tx -> {
            var job = jobs.lockJob(tenantId, id).orElse(null);
            if (job == null || job.isCompleted()
                    || (!manual && job.getNextAttemptAt().isAfter(AppTimeZone.now()))) return;
            job.setAttempts(job.getAttempts() + 1);
            try {
                Employee employee = employees.findByTenantIdAndId(tenantId, job.getEmployeeId()).orElseThrow();
                DeviceConfig device = devices.findById(job.getDeviceConfigId())
                        .filter(d -> tenantId.equals(d.getTenantId())).orElseThrow();
                if (employee.getEmploymentStatus() != Employee.EmploymentStatus.TERMINATED
                        || !Objects.equals(personNo(employee), job.getEmployeeNo())
                        || !same(device.getDeviceId(), job.getBridgeDeviceId())
                        || !same(device.getDeviceIp(), job.getDeviceIp())
                        || !same(device.getDeviceName(), job.getDeviceName())) {
                    throw new IllegalStateException("Əməkdaş və ya cihaz məlumatları dəyişib; yoxlama tələb olunur");
                }
                Long bridgeId = Long.valueOf(job.getBridgeDeviceId());
                var bridge = client.device(bridgeId);
                if (bridge == null || !bridgeId.equals(bridge.getId()) || !same(job.getDeviceIp(), bridge.getIp())
                        || !same(job.getDeviceName(), bridge.getName())) {
                    throw new IllegalStateException("Cihaz kimliyi uyğun gəlmir; yoxlama tələb olunur");
                }
                var status = client.status(bridgeId);
                if (status == null || !bridgeId.equals(status.getId()) || !status.isOnline()) {
                    throw new IllegalStateException("Cihaz offline-dır; avtomatik təkrar gözlənilir");
                }
                client.delete(bridgeId, job.getEmployeeNo());
                if (!client.absent(bridgeId, job.getEmployeeNo())) {
                    throw new IllegalStateException("Əməkdaş hələ cihazdadır; silinmə avtomatik təkrar ediləcək");
                }
                job.setCompleted(true);
                job.setCompletedAt(AppTimeZone.now());
                job.setLastError(null);
            } catch (RuntimeException ex) {
                // Do not persist upstream response bodies, device credentials or biometric data.
                job.setLastError(ex instanceof IllegalStateException ? ex.getMessage()
                        : "Cihazdan silinmə alınmadı; avtomatik təkrar gözlənilir");
                job.setNextAttemptAt(AppTimeZone.now().plusMinutes(1));
            }
            jobs.save(job);
        });
    }

    public EmployeeTerminationResultDTO result(Long tenantId, Long employeeId) {
        var all = jobs.findByTenantIdAndEmployeeIdOrderById(tenantId, employeeId);
        int removed = (int) all.stream().filter(EmployeeDeviceRemovalJob::isCompleted).count();
        List<String> errors = all.stream().filter(j -> !j.isCompleted())
                .map(j -> (j.getDeviceName() == null ? "Cihaz " + j.getDeviceConfigId() : j.getDeviceName())
                        + ": " + (j.getLastError() == null ? "Silinmə gözlənilir" : j.getLastError())).toList();
        return new EmployeeTerminationResultDTO(employeeId, all.size(), removed, all.size() - removed, errors);
    }

    private static String personNo(Employee employee) {
        return employee.getDeviceEmployeeNo() != null && !employee.getDeviceEmployeeNo().isBlank()
                ? employee.getDeviceEmployeeNo().trim() : employee.getEmployeeId();
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.trim().equalsIgnoreCase(b.trim());
    }
}
