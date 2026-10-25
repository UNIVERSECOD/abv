package com.hic.service;

import com.hic.dto.LeaveRequestDTO;
import com.hic.exception.BadRequestException;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.LeaveRequest;
import com.hic.model.LeaveRequest.LeaveStatus;
import com.hic.model.LeaveType;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TenantContext;
import com.hic.util.AppTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public List<LeaveRequestDTO> getAll() {
        Long tenantId = requireTenantId();
        var employees = employeeRepository.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenantId).stream()
                .collect(Collectors.toMap(com.hic.model.Employee::getId, Function.identity()));
        var types = leaveTypeRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(LeaveType::getId, Function.identity()));
        return leaveRequestRepository.findByTenantId(tenantId).stream()
                .map(request -> toDTO(request, employees, types))
                .collect(Collectors.toList());
    }

    public List<LeaveType> getAllLeaveTypes() {
        Long tenantId = requireTenantId();
        return leaveTypeRepository.findByTenantId(tenantId);
    }

    public List<LeaveRequestDTO> getByEmployee(Long employeeId) {
        Long tenantId = requireTenantId();
        return leaveRequestRepository.findByTenantIdAndEmployeeId(tenantId, employeeId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public LeaveRequestDTO getById(Long id) {
        return toDTO(requireLeaveInTenant(id));
    }

    public boolean hasActiveLeave(Long employeeId, LocalDate date) {
        Long tenantId = TenantContext.getTenantId();
        List<LeaveRequest> requests;
        if (tenantId != null) {
            requests = leaveRequestRepository.findApprovedByTenantAndEmployeeIdAndDateRange(
                    tenantId, employeeId, date, date);
            return !requests.isEmpty();
        }
        requests = leaveRequestRepository.findByEmployeeIdAndStatus(employeeId, LeaveStatus.APPROVED);
        return requests.stream().anyMatch(request ->
                !date.isBefore(request.getStartDate()) && !date.isAfter(request.getEndDate()));
    }

    @Transactional
    public LeaveRequestDTO create(LeaveRequestDTO dto) {
        Long tenantId = requireTenantId();
        lockEmployees(tenantId, List.of(requireEmployeeId(dto.getEmployeeId())));
        validate(dto, tenantId, null);
        validatePaidBalance(dto, tenantId, null);
        LeaveRequest req = new LeaveRequest();
        req.setEmployeeId(dto.getEmployeeId());
        req.setLeaveTypeId(dto.getLeaveTypeId());
        req.setStartDate(dto.getStartDate());
        req.setEndDate(dto.getEndDate());
        req.setStatus(dto.getStatus() == null ? LeaveStatus.PENDING : dto.getStatus());
        if (req.getStatus() == LeaveStatus.APPROVED) {
            req.setApprovedBy(TenantContext.getUserId());
            req.setApprovalDate(AppTimeZone.today());
        }
        req.setTenantId(tenantId);
        return toDTO(leaveRequestRepository.save(req));
    }

    @Transactional
    public LeaveRequestDTO updateStatus(Long id, LeaveStatus status, Long approvedBy) {
        LeaveRequest req = lockLeaveInTenant(id);
        lockEmployees(requireTenantId(), List.of(req.getEmployeeId()));
        if (status == null) throw new BadRequestException("Vəziyyəti seçin");
        if (status == LeaveStatus.APPROVED) {
            LeaveRequestDTO dto = toDTO(req);
            dto.setStatus(status);
            validate(dto, requireTenantId(), id);
            validatePaidBalance(dto, requireTenantId(), id);
        }
        req.setStatus(status);
        if (status == LeaveStatus.APPROVED) {
            req.setApprovedBy(TenantContext.getUserId());
            req.setApprovalDate(AppTimeZone.today());
        } else {
            req.setApprovedBy(null);
            req.setApprovalDate(null);
        }
        return toDTO(leaveRequestRepository.save(req));
    }

    @Transactional
    public void delete(Long id) {
        LeaveRequest req = lockLeaveInTenant(id);
        lockEmployees(requireTenantId(), List.of(req.getEmployeeId()));
        leaveRequestRepository.deleteById(req.getId());
    }

    private LeaveRequest lockLeaveInTenant(Long id) {
        return leaveRequestRepository.lockInTenant(id, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", id));
    }

    private Long requireEmployeeId(Long id) {
        if (id == null) throw new BadRequestException("Əməkdaş seçin");
        return id;
    }

    // Serialize balance-changing operations per employee; sorted order also protects bulk imports.
    void lockEmployees(Long tenantId, List<Long> employeeIds) {
        employeeIds.stream().distinct().sorted().forEach(id -> {
            var employee = employeeRepository.lockForLeave(tenantId, id)
                    .orElseThrow(() -> new BadRequestException("Əməkdaş tapılmadı"));
            // Import may have loaded staff before waiting for this lock: discard that stale snapshot.
            entityManager.refresh(employee, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        });
    }

    private void validatePaidBalance(LeaveRequestDTO dto, Long tenantId, Long excludedId) {
        if (dto.getStatus() != LeaveStatus.APPROVED) return;
        var annualTypeIds = leaveTypeRepository.findByTenantId(tenantId).stream()
                .filter(LeaveType::debitsBalance)
                .map(LeaveType::getId).collect(Collectors.toSet());
        if (!annualTypeIds.contains(dto.getLeaveTypeId())) return;
        var employee = employeeRepository.findByTenantIdAndId(tenantId, dto.getEmployeeId())
                .orElseThrow(() -> new BadRequestException("Əməkdaş tapılmadı"));
        var candidate = new LeaveRequest();
        candidate.setStartDate(dto.getStartDate()); candidate.setEndDate(dto.getEndDate());
        candidate.setStatus(LeaveStatus.APPROVED);
        int requested = LeaveBalanceService.countDays(List.of(candidate), employee.getLeaveBalanceStartDate());
        if (requested == 0) return; // Historical dates before the opening balance do not debit it.
        if (employee.getAnnualLeaveBalance() == null) {
            throw new BadRequestException("Məzuniyyətin başlanğıc qalığı daxil edilməyib. Əvvəl işçi kartında qalığı daxil edin.");
        }
        var existing = leaveRequestRepository.findByTenantIdAndEmployeeId(tenantId, dto.getEmployeeId()).stream()
                .filter(r -> !java.util.Objects.equals(r.getId(), excludedId))
                .filter(r -> annualTypeIds.contains(r.getLeaveTypeId())).toList();
        int remaining = employee.getAnnualLeaveBalance()
                - LeaveBalanceService.countDays(existing, employee.getLeaveBalanceStartDate());
        if (requested > remaining) {
            throw new BadRequestException("Məzuniyyət balansı kifayət etmir. Qalıq: " + remaining
                    + " gün, tələb olunan: " + requested + " gün. Balansdan çıxılmayan məzuniyyət növü seçə bilərsiniz.");
        }
    }

    private LeaveRequest requireLeaveInTenant(Long id) {
        LeaveRequest req = leaveRequestRepository.findByIdAndTenantId(id, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", id));
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && (req.getTenantId() == null || !tenantId.equals(req.getTenantId()))) {
            throw new ResourceNotFoundException("LeaveRequest", id);
        }
        return req;
    }

    void validate(LeaveRequestDTO dto, Long tenantId, Long excludedId) {
        if (dto.getEmployeeId() == null || employeeRepository.findByTenantIdAndId(tenantId, dto.getEmployeeId()).isEmpty()) {
            throw new BadRequestException("Əməkdaş tapılmadı");
        }
        if (dto.getLeaveTypeId() == null || leaveTypeRepository.findByTenantId(tenantId).stream()
                .noneMatch(type -> type.getId().equals(dto.getLeaveTypeId()))) {
            throw new BadRequestException("Məzuniyyət növü tapılmadı");
        }
        var chosenType = leaveTypeRepository.findByTenantId(tenantId).stream()
                .filter(t -> t.getId().equals(dto.getLeaveTypeId())).findFirst().orElseThrow();
        if (chosenType.isArchived() && (excludedId == null || leaveRequestRepository
                .findByIdAndTenantId(excludedId, tenantId).filter(r -> r.getLeaveTypeId().equals(chosenType.getId())).isEmpty())) {
            throw new BadRequestException("Silinmiş məzuniyyət növünü seçmək olmaz");
        }
        if (dto.getStartDate() == null || dto.getEndDate() == null || dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new BadRequestException("Düzgün tarix aralığı seçin");
        }
        if (leaveRequestRepository
                .findByTenantIdAndEmployeeId(tenantId, dto.getEmployeeId()).stream()
                .anyMatch(existing -> !existing.getId().equals(excludedId)
                        && ((existing.getLeaveTypeId().equals(dto.getLeaveTypeId())
                             && existing.getStartDate().equals(dto.getStartDate())
                             && existing.getEndDate().equals(dto.getEndDate()))
                            || (dto.getStatus() != LeaveStatus.REJECTED && existing.getStatus() != LeaveStatus.REJECTED
                                && !existing.getStartDate().isAfter(dto.getEndDate())
                                && !existing.getEndDate().isBefore(dto.getStartDate()))))) {
            throw new BadRequestException("Əməkdaşın bu tarixlərdə artıq məzuniyyəti var");
        }
    }

    @Transactional
    public LeaveRequestDTO update(Long id, LeaveRequestDTO dto) {
        LeaveRequest req = lockLeaveInTenant(id);
        lockEmployees(requireTenantId(), List.of(req.getEmployeeId(), requireEmployeeId(dto.getEmployeeId())));
        if (dto.getStatus() == null) dto.setStatus(req.getStatus());
        validate(dto, requireTenantId(), id);
        validatePaidBalance(dto, requireTenantId(), id);
        req.setEmployeeId(dto.getEmployeeId());
        req.setLeaveTypeId(dto.getLeaveTypeId());
        req.setStartDate(dto.getStartDate());
        req.setEndDate(dto.getEndDate());
        if (dto.getStatus() != null) req.setStatus(dto.getStatus());
        req.setApprovedBy(req.getStatus() == LeaveStatus.APPROVED ? TenantContext.getUserId() : null);
        req.setApprovalDate(req.getStatus() == LeaveStatus.APPROVED ? AppTimeZone.today() : null);
        return toDTO(leaveRequestRepository.save(req));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BadRequestException("Tenant context is required");
        }
        return tenantId;
    }

    private LeaveRequestDTO toDTO(LeaveRequest req) {
        var employee = employeeRepository.findByTenantIdAndId(req.getTenantId(), req.getEmployeeId());
        var types = leaveTypeRepository.findByTenantId(req.getTenantId()).stream()
                .collect(Collectors.toMap(LeaveType::getId, Function.identity()));
        return toDTO(req, employee.map(e -> Map.of(e.getId(), e)).orElse(Map.of()), types);
    }

    private LeaveRequestDTO toDTO(LeaveRequest req, Map<Long, com.hic.model.Employee> employees,
                                 Map<Long, LeaveType> types) {
        LeaveRequestDTO dto = new LeaveRequestDTO();
        dto.setId(req.getId());
        dto.setEmployeeId(req.getEmployeeId());
        dto.setLeaveTypeId(req.getLeaveTypeId());
        dto.setStartDate(req.getStartDate());
        dto.setEndDate(req.getEndDate());
        dto.setStatus(req.getStatus());
        dto.setApprovedBy(req.getApprovedBy());
        dto.setApprovalDate(req.getApprovalDate());
        dto.setCreatedAt(req.getCreatedAt());
        var employee = employees.get(req.getEmployeeId());
        if (employee != null) {
            dto.setEmployeeCode(employee.getEmployeeId());
            dto.setEmployeeName(employee.getLastName() + " " + employee.getFirstName());
            dto.setFinNumber(employee.getFinNumber());
        }
        var type = types.get(req.getLeaveTypeId());
        if (type != null) dto.setLeaveTypeName(type.displayName());
        return dto;
    }
}
