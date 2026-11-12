package com.hic.service;

import com.hic.dto.AttendanceReportRowDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.exception.BadRequestException;
import com.hic.model.AttendanceLog;
import com.hic.model.Branch;
import com.hic.model.Department;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.model.Position;
import com.hic.model.Timetable;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.BranchRepository;
import com.hic.repository.DepartmentRepository;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.FaceDataRepository;
import com.hic.repository.PositionRepository;
import com.hic.repository.TimetableRepository;
import com.hic.util.AppTimeZone;
import com.hic.util.ShiftTypes;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceReportService {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AttendanceLogRepository attendanceLogRepository;
    private final EmployeeRepository employeeRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final BranchRepository branchRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final FaceDataRepository faceDataRepository;
    private final TimetableRepository timetableRepository;
    private final EmployeeShiftResolver employeeShiftResolver;
    private final AttendanceSessionPolicy attendanceSessionPolicy;

    public PaginatedResponse<AttendanceReportRowDTO> getReport(
            LocalDate start,
            LocalDate end,
            String shiftType,
            String employeeCode,
            String name,
            String fin,
            String position,
            String department,
            String area,
            int page,
            int size
    ) {
        if (page < 0) {
            throw new BadRequestException("Səhifə nömrəsi 0 və ya daha böyük olmalıdır.");
        }
        if (size < 1) {
            throw new BadRequestException("Səhifə ölçüsü ən azı 1 olmalıdır.");
        }
        List<AttendanceReportRowDTO> allRows = queryRows(
                start, end, shiftType, employeeCode, name, fin, position, department, area
        );
        int fromIndex = (int) Math.min((long) page * size, allRows.size());
        int toIndex = (int) Math.min((long) fromIndex + size, allRows.size());
        int totalPages = (int) Math.ceil((double) allRows.size() / size);
        return PaginatedResponse.of(allRows.subList(fromIndex, toIndex), allRows.size(), totalPages, page, size);
    }

    public byte[] exportExcel(
            LocalDate start,
            LocalDate end,
            String shiftType,
            String employeeCode,
            String name,
            String fin,
            String position,
            String department,
            String area
    ) {
        List<AttendanceReportRowDTO> rows = queryRows(start, end, shiftType, employeeCode, name, fin, position, department, area);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Attendance Reports");
            Row header = sheet.createRow(0);
            String[] headers = {
                    "ID", "Name", "FIN", "Department", "Position", "Area",
                    "Check-in", "Check-out", "Worked", "Method", "Shift", "Status"
            };
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
                sheet.setColumnWidth(i, i == 6 || i == 7 ? 6500 : 5500);
            }

            int rowNum = 1;
            for (AttendanceReportRowDTO row : rows) {
                Row excelRow = sheet.createRow(rowNum++);
                excelRow.createCell(0).setCellValue(safe(row.getEmployeeId()));
                excelRow.createCell(1).setCellValue(safe(row.getFullName()));
                excelRow.createCell(2).setCellValue(safe(row.getFin()));
                excelRow.createCell(3).setCellValue(safe(row.getDepartment()));
                excelRow.createCell(4).setCellValue(safe(row.getPosition()));
                excelRow.createCell(5).setCellValue(safe(row.getArea()));
                excelRow.createCell(6).setCellValue(formatDateTime(row.getCheckInTime()));
                excelRow.createCell(7).setCellValue(formatDateTime(row.getCheckOutTime()));
                excelRow.createCell(8).setCellValue(formatDuration(row.getWorkedMinutes()));
                excelRow.createCell(9).setCellValue(safe(row.getVerificationMethod()));
                excelRow.createCell(10).setCellValue(safe(row.getShiftType()));
                excelRow.createCell(11).setCellValue(safe(row.getStatus()));
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to export attendance report", e);
        }
    }

    private List<AttendanceReportRowDTO> queryRows(
            LocalDate start,
            LocalDate end,
            String shiftType,
            String employeeCode,
            String name,
            String fin,
            String position,
            String department,
            String area
    ) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new BadRequestException("Başlanğıc tarixi bitmə tarixindən sonra ola bilməz.");
        }
        Long tenantId = TenantContext.getTenantId();

        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.atTime(LocalTime.MAX);

        List<AttendanceLog> logs = tenantId != null
                ? attendanceLogRepository.findByTenantIdAndCheckInTimeBetween(tenantId, startDt, endDt)
                : attendanceLogRepository.findByCheckInTimeBetween(startDt, endDt);
        if (tenantId != null) {
            logs = new ArrayList<>(logs);
            logs.addAll(attendanceLogRepository.findManagedLateEntries(tenantId, null, start, end,
                    end.plusDays(1).atStartOfDay()));
        }

        Set<Long> employeeIds = logs.stream()
                .map(AttendanceLog::getEmployeeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Employee> employeeMap = employeeRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getId, e -> e));

        Map<Long, List<com.hic.model.EmployeeShiftAssignment>> assignmentsByEmployee = tenantId != null
                ? employeeShiftResolver.loadAssignmentsByEmployee(tenantId, employeeIds, start.minusDays(1), end)
                : Map.of();

        Set<Long> timetableIds = employeeShiftResolver.collectTimetableIds(employeeMap.values(), assignmentsByEmployee);
        Map<Long, Timetable> timetableMap = timetableIds.isEmpty()
                ? Map.of()
                : timetableRepository.findAllById(timetableIds).stream()
                .collect(Collectors.toMap(Timetable::getId, t -> t, (left, right) -> left, HashMap::new));

        Set<Long> departmentIds = employeeMap.values().stream()
                .map(Employee::getDepartmentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> positionIds = employeeMap.values().stream()
                .map(Employee::getPositionId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, String> departmentNames = departmentRepository.findAllById(departmentIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getDepartmentName));
        Map<Long, String> positionNames = positionRepository.findAllById(positionIds).stream()
                .collect(Collectors.toMap(Position::getId, Position::getPositionName));

        List<DeviceConfig> devices = tenantId != null
                ? deviceConfigRepository.findByTenantId(tenantId)
                : deviceConfigRepository.findAll();
        Set<Long> branchIds = devices.stream()
                .map(DeviceConfig::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        branchIds.addAll(employeeMap.values().stream()
                .map(Employee::getBranchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, String> branchNames = branchRepository.findAllById(branchIds).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));
        Map<String, String> areaNamesByDeviceIdentifier = buildAreaNamesByDeviceIdentifier(devices, branchNames);

        Map<Long, List<AttendanceLog>> logsByEmployee = logs.stream()
                .filter(log -> log.getEmployeeId() != null && log.getCheckInTime() != null)
                .collect(Collectors.groupingBy(AttendanceLog::getEmployeeId));

        Predicate<AttendanceReportRowDTO> predicate = dto ->
                contains(dto.getEmployeeId(), employeeCode) &&
                        contains(dto.getFullName(), name) &&
                        contains(dto.getFin(), fin) &&
                        contains(dto.getPosition(), position) &&
                        contains(dto.getDepartment(), department) &&
                        contains(dto.getArea(), area) &&
                        ShiftTypes.matchesFilter(dto.getShiftType(), shiftType);

        List<AttendanceReportRowDTO> rows = new ArrayList<>();
        for (Map.Entry<Long, List<AttendanceLog>> entry : logsByEmployee.entrySet()) {
            Employee employee = employeeMap.get(entry.getKey());
            if (employee == null) {
                continue;
            }

            List<AttendanceLog> employeeLogs = reportSessions(
                    entry.getValue().stream()
                            .filter(log -> log.getCheckInTime() != null)
                            .filter(log -> overlapsReportRange(log, start, end))
                            .sorted(Comparator.comparing(AttendanceLog::getCheckInTime)
                                    .thenComparing(log -> log.getId() != null ? log.getId() : 0L))
                            .toList()
            );

            for (AttendanceLog log : employeeLogs) {
                EmployeeShiftResolver.ResolvedShift resolved = employeeShiftResolver.resolveForLog(
                        employee, log, assignmentsByEmployee, timetableMap);
                String scheduleShiftType = resolved.shiftType() != null ? resolved.shiftType() : employee.getShiftType();
                AttendanceReportRowDTO dto = buildSessionRow(
                        employee,
                        log,
                        scheduleShiftType,
                        departmentNames,
                        positionNames,
                        branchNames,
                        areaNamesByDeviceIdentifier);
                if (predicate.test(dto)) {
                    rows.add(dto);
                }
            }
        }

        return rows.stream()
                .sorted(Comparator.comparing(AttendanceReportRowDTO::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AttendanceReportRowDTO::getCheckInTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Report projection only: never delete source logs or change the daily/Tabel calculation. */
    private List<AttendanceLog> reportSessions(List<AttendanceLog> ordered) {
        List<AttendanceLog> sessions = new ArrayList<>();
        Set<SessionIdentity> seen = new java.util.HashSet<>();
        AttendanceLog pending = null;
        for (AttendanceLog log : ordered) {
            // Aggregated sessions already represent an independent area and work date.
            if (log.getCombinedAreaId() != null || log.getAnprAreaId() != null) {
                sessions.add(log);
                continue;
            }
            boolean manual = Boolean.TRUE.equals(log.getManualOverride());
            SessionIdentity identity = new SessionIdentity(log.getCheckInTime(), log.getCheckOutTime(), log.getDeviceId());
            // Only exact copies are duplicates. Two completed visits seconds apart are distinct.
            if (!manual && log.getCheckOutTime() != null && !seen.add(identity)) continue;
            if (manual) {
                if (pending != null) sessions.add(pending);
                pending = null;
                sessions.add(log);
            } else if (log.getCheckOutTime() == null) {
                // Repeated entries without an exit update the same visible row to the latest entry.
                if (pending == null || !reportEntry(log).isBefore(reportEntry(pending))) pending = log;
            } else {
                sessions.add(log);
                pending = null;
            }
        }
        if (pending != null) sessions.add(pending);
        return sessions;
    }

    private record SessionIdentity(LocalDateTime entry, LocalDateTime exit, String device) {}

    private LocalDateTime reportEntry(AttendanceLog log) {
        LocalDateTime latest = log.getLastEntryTime();
        return !Boolean.TRUE.equals(log.getManualOverride()) && latest != null
                && !latest.isBefore(log.getCheckInTime())
                && (log.getCheckOutTime() == null || latest.isBefore(log.getCheckOutTime()))
                ? latest : log.getCheckInTime();
    }

    private AttendanceReportRowDTO buildSessionRow(
            Employee employee,
            AttendanceLog log,
            String scheduleShiftType,
            Map<Long, String> departmentNames,
            Map<Long, String> positionNames,
            Map<Long, String> branchNames,
            Map<String, String> areaNamesByDeviceIdentifier
    ) {
        AttendanceReportRowDTO dto = baseEmployeeRow(employee, departmentNames, positionNames, branchNames);
        dto.setArea(resolveLogArea(log, areaNamesByDeviceIdentifier, dto.getArea()));
        dto.setAttendanceLogId(log.getId());
        dto.setDate(AttendanceDayAllocation.workDate(log));
        LocalDateTime entry = reportEntry(log);
        dto.setCheckInTime(toOffsetDateTime(entry));
        dto.setCheckOutTime(toOffsetDateTime(log.getCheckOutTime()));
        if (log.getCheckOutTime() != null && log.getCheckOutTime().isAfter(entry)) {
            dto.setWorkedMinutes((int) Duration.between(entry, log.getCheckOutTime()).toMinutes());
        } else {
            dto.setWorkedMinutes(0);
        }
        dto.setVerificationMethod(normalizeVerificationMethod(log.getVerificationMethod()));
        dto.setEntryMethod(entry.equals(log.getCheckInTime()) ? log.getEntryMethod() : log.getLastEntryMethod());
        dto.setExitMethod(log.getExitMethod());
        dto.setShiftType(scheduleShiftType);
        dto.setStatus(attendanceSessionPolicy.effectiveStatus(employee, log));
        return dto;
    }

    private AttendanceReportRowDTO baseEmployeeRow(
            Employee employee,
            Map<Long, String> departmentNames,
            Map<Long, String> positionNames,
            Map<Long, String> branchNames
    ) {
        AttendanceReportRowDTO dto = new AttendanceReportRowDTO();
        dto.setEmployeePk(employee.getId());
        dto.setEmployeeId(employee.getEmployeeId());
        dto.setFullName((safe(employee.getFirstName()) + " " + safe(employee.getLastName())).trim());
        dto.setFin(employee.getFinNumber());
        dto.setDepartment(departmentNames.get(employee.getDepartmentId()));
        dto.setPosition(positionNames.get(employee.getPositionId()));
        dto.setArea(branchNames.get(employee.getBranchId()));
        faceDataRepository.findTopByEmployeeIdOrderByCreatedAtDesc(employee.getId())
                .ifPresent(face -> dto.setPhotoUrl("/api/faces/employee/" + employee.getId() + "/image"));
        return dto;
    }

    private Map<String, String> buildAreaNamesByDeviceIdentifier(
            List<DeviceConfig> devices,
            Map<Long, String> branchNames
    ) {
        Map<String, String> result = new HashMap<>();
        for (DeviceConfig device : devices) {
            String areaName = branchNames.get(device.getBranchId());
            if (areaName == null || areaName.isBlank()) {
                continue;
            }
            if (device.getId() != null) {
                result.put(String.valueOf(device.getId()), areaName);
            }
            if (device.getDeviceId() != null && !device.getDeviceId().isBlank()) {
                result.put(device.getDeviceId().trim(), areaName);
            }
        }
        return result;
    }

    private String resolveLogArea(
            AttendanceLog log,
            Map<String, String> areaNamesByDeviceIdentifier,
            String fallback
    ) {
        if (log != null && Boolean.TRUE.equals(log.getAreaSnapshotCaptured())) {
            return log.getAreaSnapshotName() != null ? log.getAreaSnapshotName() : "Ərazi məlum deyil";
        }
        if (log != null && log.getDeviceId() != null && !log.getDeviceId().isBlank()) {
            String areaName = areaNamesByDeviceIdentifier.get(log.getDeviceId().trim());
            if (areaName != null && !areaName.isBlank()) {
                return areaName;
            }
        }
        return fallback;
    }

    /** A session is reported in the period containing its check-in work date. */
    private boolean overlapsReportRange(AttendanceLog log, LocalDate start, LocalDate end) {
        LocalDateTime entry = log.getCheckInTime();
        if (entry == null) {
            return false;
        }
        LocalDate workDate = AttendanceDayAllocation.workDate(log);
        return !workDate.isBefore(start) && !workDate.isAfter(end);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime localDateTime) {
        return AppTimeZone.toOffsetDateTime(localDateTime);
    }

    private boolean contains(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return safe(value).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String formatDuration(Integer workedMinutes) {
        if (workedMinutes == null || workedMinutes <= 0) {
            return "";
        }
        int hours = workedMinutes / 60;
        int minutes = workedMinutes % 60;
        return String.format("%02d:%02d", hours, minutes);
    }

    /** Full date+time so overnight flexible/night sessions show distinct calendar days. */
    private String formatDateTime(OffsetDateTime value) {
        if (value == null) {
            return "";
        }
        return value.toLocalDateTime().withNano(0).format(DATE_TIME_FORMAT);
    }

    private String normalizeVerificationMethod(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        String lower = method.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("face")) {
            return "face";
        }
        if (lower.contains("card") || lower.contains("mifare")) {
            return "card";
        }
        if (lower.contains("finger")) {
            return "finger";
        }
        if ("isapi_punch".equals(lower) || "door_session".equals(lower)) {
            return "device";
        }
        return lower;
    }
}
