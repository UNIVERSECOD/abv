package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.LeaveType;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LeaveTypeService {
    private final LeaveTypeRepository types;
    private final com.hic.repository.LeaveRequestRepository requests;

    public record Input(String leaveName, String shortName, boolean deductFromBalance) {}

    @Transactional
    public LeaveType save(Long id, Input input) {
        Long tenant = tenant();
        LeaveType type = id == null ? new LeaveType() : require(id, tenant);
        String name = input.leaveName() == null ? "" : input.leaveName().strip();
        String abbreviation = input.shortName() == null ? "" : input.shortName().strip().toUpperCase(Locale.forLanguageTag("az"));
        if (name.isBlank() || name.length() > 200) throw new BadRequestException("Məzuniyyətin adı 1–200 simvol olmalıdır");
        if (!abbreviation.matches("\\p{L}{1,3}")) throw new BadRequestException("Tabel qısaltması 1–3 hərfdən ibarət olmalıdır");
        if (types.findByTenantId(tenant).stream().anyMatch(t -> !Objects.equals(t.getId(), id)
                && !t.isArchived() && (t.getLeaveName().equalsIgnoreCase(name)
                || abbreviation.equalsIgnoreCase(t.getShortName())))) {
            throw new BadRequestException("Bu ad və ya qısaltma ilə məzuniyyət növü artıq var");
        }
        if (type.isArchived()) throw new BadRequestException("Silinmiş məzuniyyət növünü dəyişmək olmaz");
        if (id != null && type.debitsBalance() != input.deductFromBalance()
                && requests.findByTenantId(tenant).stream().anyMatch(r -> id.equals(r.getLeaveTypeId()))) {
            throw new BadRequestException("İstifadə edilmiş növün balans qaydasını dəyişmək olmaz. Yeni qayda üçün yeni növ yaradın.");
        }
        type.setTenantId(tenant);
        if (id == null) type.setLeaveCode("CUSTOM_" + UUID.randomUUID());
        type.setLeaveName(name);
        type.setShortName(abbreviation);
        type.setDeductFromBalance(input.deductFromBalance());
        return types.save(type);
    }

    @Transactional
    public void delete(Long id) {
        LeaveType type = require(id, tenant());
        // Retain referenced types so historical requests, reports and balances remain readable.
        type.setArchived(true);
        types.save(type);
    }

    private LeaveType require(Long id, Long tenant) {
        return types.findByTenantId(tenant).stream().filter(t -> t.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("LeaveType", id));
    }

    private Long tenant() {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null) throw new BadRequestException("Tenant context is required");
        return tenant;
    }
}
