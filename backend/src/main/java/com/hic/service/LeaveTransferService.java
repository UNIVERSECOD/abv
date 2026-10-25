package com.hic.service;

import com.hic.dto.*;
import com.hic.exception.BadRequestException;
import com.hic.model.Employee;
import com.hic.model.LeaveRequest.LeaveStatus;
import com.hic.model.LeaveType;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.LeaveTypeRepository;
import com.hic.util.TabularFileUtil;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LeaveTransferService {
    static final List<String> HEADERS = List.of("Əməkdaş ID", "Əməkdaş", "FIN", "Məzuniyyət növü",
            "Başlanğıc tarixi", "Bitmə tarixi", "Vəziyyət");
    private final TabularFileUtil files;
    private final LeaveService leaves;
    private final EmployeeRepository employees;
    private final LeaveTypeRepository types;

    @Transactional(readOnly = true)
    public byte[] export(boolean template) {
        Long tenantId = tenant();
        if (template) {
            var staff = employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenantId);
            Map<String, List<String>> choices = new LinkedHashMap<>();
            choices.put("Əməkdaş ID", staff.stream().map(Employee::getEmployeeId).filter(Objects::nonNull).toList());
            choices.put("Məzuniyyət növü", types.findByTenantId(tenantId).stream().filter(t -> !t.isArchived()).map(LeaveType::displayName).toList());
            choices.put("Vəziyyət", List.of("Təsdiqlənib", "Gözləmədə", "Rədd edilib"));
            List<List<String>> reference = new ArrayList<>();
            reference.add(List.of("Əməkdaş ID", "Əməkdaş", "FIN"));
            staff.forEach(e -> reference.add(List.of(text(e.getEmployeeId()), e.getLastName() + " " + e.getFirstName(), text(e.getFinNumber()))));
            return files.writeExcelTemplate("Məzuniyyətlər", HEADERS,
                    Set.of("Əməkdaş ID", "Məzuniyyət növü", "Başlanğıc tarixi", "Bitmə tarixi"),
                    choices, reference, TabularFileUtil.LEAVE_TEMPLATE_NOTE);
        }
        return files.writeExcel("Məzuniyyətlər", HEADERS, leaves.getAll().stream()
                .sorted(Comparator.comparing(LeaveRequestDTO::getStartDate).reversed())
                .map(l -> List.of(text(l.getEmployeeCode()), text(l.getEmployeeName()), text(l.getFinNumber()),
                        text(l.getLeaveTypeName()), l.getStartDate().toString(), l.getEndDate().toString(), statusLabel(l.getStatus())))
                .toList());
    }

    @Transactional
    public DataImportResultDTO importFile(MultipartFile file) {
        Long tenantId = tenant();
        String name = text(file == null ? null : file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) throw new BadRequestException("Excel faylı seçin (.xlsx və ya .xls)");
        var table = files.read(file);
        for (String header : List.of("Əməkdaş ID", "Məzuniyyət növü", "Başlanğıc tarixi", "Bitmə tarixi")) {
            if (!table.hasHeader(header)) throw new BadRequestException("Başlıq yoxdur: " + header);
        }
        var staff = employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenantId);
        var requestedCodes = table.rows().stream().map(row -> row.get("Əməkdaş ID").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        leaves.lockEmployees(tenantId, staff.stream()
                .filter(e -> requestedCodes.contains(text(e.getEmployeeId()).toLowerCase(Locale.ROOT)))
                .map(Employee::getId).toList());
        var leaveTypes = types.findByTenantId(tenantId).stream().filter(type -> !type.isArchived()).toList();
        var requests = new ArrayList<LeaveRequestDTO>();
        var requestRows = new ArrayList<Integer>();
        var errors = new ArrayList<DataImportErrorDTO>();
        for (var row : table.rows()) {
            try {
                var matches = staff.stream().filter(e -> text(e.getEmployeeId()).equalsIgnoreCase(row.get("Əməkdaş ID"))).toList();
                if (matches.size() != 1) throw new BadRequestException("Əməkdaş ID tapılmadı və ya unikal deyil");
                Employee employee = matches.get(0);
                if (!row.get("FIN").isBlank() && !row.get("FIN").equalsIgnoreCase(text(employee.getFinNumber()))) {
                    throw new BadRequestException("FIN seçilmiş əməkdaşa uyğun deyil");
                }
                var matchingTypes = matchingTypes(leaveTypes, row.get("Məzuniyyət növü"));
                if (matchingTypes.size() != 1) throw new BadRequestException("Məzuniyyət növü tapılmadı və ya unikal deyil");
                var dto = new LeaveRequestDTO();
                dto.setEmployeeId(employee.getId());
                dto.setLeaveTypeId(matchingTypes.get(0).getId());
                dto.setStartDate(date(row.get("Başlanğıc tarixi")));
                dto.setEndDate(date(row.get("Bitmə tarixi")));
                dto.setStatus(status(row.get("Vəziyyət")));
                leaves.validate(dto, tenantId, null);
                if (requests.stream().anyMatch(previous -> previous.getEmployeeId().equals(dto.getEmployeeId())
                        && ((previous.getLeaveTypeId().equals(dto.getLeaveTypeId())
                             && previous.getStartDate().equals(dto.getStartDate()) && previous.getEndDate().equals(dto.getEndDate()))
                            || (dto.getStatus() != LeaveStatus.REJECTED && previous.getStatus() != LeaveStatus.REJECTED
                                && !previous.getStartDate().isAfter(dto.getEndDate()) && !previous.getEndDate().isBefore(dto.getStartDate()))))) {
                    throw new BadRequestException("Faylda eyni əməkdaşın məzuniyyət tarixləri üst-üstə düşür");
                }
                requests.add(dto);
                requestRows.add(row.rowNumber());
            } catch (BadRequestException ex) {
                errors.add(new DataImportErrorDTO(row.rowNumber(), "Məzuniyyət", ex.getMessage()));
            }
        }
        if (errors.isEmpty()) {
            // Different bulk files can touch the same types in different row orders.
            leaves.lockTypes(tenantId, requests.stream().map(LeaveRequestDTO::getLeaveTypeId).toList());
            for (int i = 0; i < requests.size(); i++) {
                try {
                    leaves.create(requests.get(i));
                } catch (BadRequestException ex) {
                    // Throw out of the transaction so all earlier rows roll back as well.
                    throw new BadRequestException("Sətir " + requestRows.get(i) + ": " + ex.getMessage()
                            + " Heç bir məlumat yazılmadı.");
                }
            }
        }
        return DataImportResultDTO.builder().entity("leaves").totalRows(table.rows().size())
                .importedRows(errors.isEmpty() ? requests.size() : 0)
                .rejectedRows(errors.isEmpty() ? 0 : table.rows().size()).errors(errors).build();
    }

    private static List<LeaveType> matchingTypes(List<LeaveType> types, String value) {
        var exact = types.stream().filter(type -> type.getLeaveName().equalsIgnoreCase(value)
                || type.getLeaveCode().equalsIgnoreCase(value) || type.displayName().equalsIgnoreCase(value)).toList();
        if (!exact.isEmpty()) return exact;
        // Old exports/templates remain usable after V044 or an administrator renames a built-in type.
        String legacyCode = "İllik məzuniyyət".equalsIgnoreCase(value) || "Ödənişli məzuniyyət".equalsIgnoreCase(value)
                ? "ANNUAL" : "Ödənişsiz məzuniyyət".equalsIgnoreCase(value) ? "UNPAID" : null;
        return legacyCode == null ? List.of() : types.stream()
                .filter(type -> legacyCode.equalsIgnoreCase(type.getLeaveCode())).toList();
    }

    private static LocalDate date(String value) {
        for (String pattern : List.of("uuuu-MM-dd", "d.M.uuuu", "d/M/uuuu", "M/d/uuuu", "M/d/uu", "d-MMM-uuuu", "d-MMM-uu")) {
            try { return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)); }
            catch (DateTimeParseException ignored) { }
        }
        throw new BadRequestException("Tarix düzgün deyil: YYYY-MM-DD formatından istifadə edin");
    }

    private static LeaveStatus status(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('İ', 'I')) {
            case "", "APPROVED", "TƏSDIQLƏNIB" -> LeaveStatus.APPROVED;
            case "PENDING", "GÖZLƏMƏDƏ" -> LeaveStatus.PENDING;
            case "REJECTED", "RƏDD EDILIB" -> LeaveStatus.REJECTED;
            default -> throw new BadRequestException("Vəziyyət düzgün deyil");
        };
    }

    private static String statusLabel(LeaveStatus status) {
        return switch (status) { case APPROVED -> "Təsdiqlənib"; case PENDING -> "Gözləmədə"; case REJECTED -> "Rədd edilib"; };
    }

    private static Long tenant() {
        Long id = TenantContext.getTenantId();
        if (id == null) throw new BadRequestException("Tenant context is required");
        return id;
    }

    private static String text(String value) { return value == null ? "" : value; }
}
