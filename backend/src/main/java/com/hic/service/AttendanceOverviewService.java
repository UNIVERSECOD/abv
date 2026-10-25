package com.hic.service;

import com.hic.dto.*;
import com.hic.exception.BadRequestException;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.AppTimeZone;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only views over the existing attendance calculations. Does not recalculate or persist totals. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceOverviewService {
    private final EmployeeRepository employees;
    private final EmployeeAreaRepository employeeAreas;
    private final AttendanceLogRepository logs;
    private final DepartmentRepository departments;
    private final PositionRepository positions;
    private final BranchRepository branches;
    private final TimetableRepository timetables;
    private final AttendanceService attendance;
    private final AttendanceReportService details;
    private final AttendanceScheduleResolver scheduleResolver;
    private final AttendanceInferenceService inferenceService;
    private final UserScopeService userScope;
    private final LeaveRequestRepository leaveRequests;

    public PaginatedResponse<AttendanceOverviewRowDTO> report(AttendanceReportQuery query) {
        List<AttendanceOverviewRowDTO> rows = AttendanceReportSort.apply(rows(query), query);
        int from = (int) Math.min((long) query.getPage() * query.getSize(), rows.size());
        int to = Math.min(from + query.getSize(), rows.size());
        return PaginatedResponse.of(rows.subList(from, to), rows.size(),
                (int) Math.ceil((double) rows.size() / query.getSize()), query.getPage(), query.getSize());
    }

    private List<AttendanceOverviewRowDTO> rows(AttendanceReportQuery q) {
        validate(q);
        Long tenant = Objects.requireNonNull(TenantContext.getTenantId(), "Tenant context is required");
        Long scope = userScope.resolveBranchScope(null);
        List<Employee> selected = employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(tenant).stream()
                .filter(e -> tenant.equals(e.getTenantId()))
                .filter(e -> scope == null || scope.equals(e.getBranchId()))
                .filter(e -> contains(e.getFirstName() + " " + e.getLastName(), q.getName()))
                .filter(e -> contains(e.getFinNumber(), q.getFin()))
                .filter(e -> q.getDepartmentId() == null || q.getDepartmentId().equals(e.getDepartmentId()))
                .filter(e -> q.getPositionId() == null || q.getPositionId().equals(e.getPositionId()))
                .toList();
        if (selected.isEmpty()) return List.of();
        var employeeMap = index(selected, Employee::getId);
        var approvedLeaves = leaveRequests.findApprovedByTenantAndEmployeeIdsAndDateRange(
                tenant, selected.stream().map(Employee::getId).toList(), q.getStart(), q.getEnd()).stream()
                .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));
        var departmentMap = index(departments.findByTenantId(tenant), Department::getId);
        var positionMap = index(positions.findByTenantId(tenant), Position::getId);
        var branchMap = index(branches.findByTenantId(tenant), Branch::getId);
        var timetableMap = index(timetables.findByTenantId(tenant), Timetable::getId);
        var memberships = employeeAreas.findByTenantIdAndEmployeeIdIn(tenant, employeeMap.keySet()).stream()
                .filter(a -> tenant.equals(a.getTenantId()))
                .collect(Collectors.groupingBy(EmployeeArea::getEmployeeId));
        List<AttendanceLog> source = logs.findByTenantIdAndCheckInTimeBetween(tenant,
                q.getStart().atStartOfDay(), q.getEnd().atTime(LocalTime.MAX));
        source = new ArrayList<>(source);
        source.addAll(logs.findClosedCarryIn(tenant, null, q.getStart().atStartOfDay()));
        var sourceById = index(source, AttendanceLog::getId);
        var earliest = source.stream().map(AttendanceLog::getCheckInTime).filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate).min(LocalDate::compareTo).orElse(q.getStart());
        var schedules = scheduleResolver.forReport(tenant, selected,
                earliest.isBefore(q.getStart().minusDays(1)) ? earliest : q.getStart().minusDays(1), q.getEnd());
        Map<DayKey, List<AttendanceLog>> sourceByDay = new HashMap<>();
        for (var log : source) {
            if (log.getCheckInTime() == null || !employeeMap.containsKey(log.getEmployeeId())) continue;
            String shift = log.getShiftType();
            if (shift == null || shift.isBlank())
                shift = schedules.apply(employeeMap.get(log.getEmployeeId()), log.getCheckInTime().toLocalDate()).shiftType();
            for (var day : AttendanceDayAllocation.affectedDates(log.getCheckInTime(), log.getCheckOutTime(), shift))
                sourceByDay.computeIfAbsent(new DayKey(log.getEmployeeId(), day), ignored -> new ArrayList<>()).add(log);
        }
        List<AttendanceOverviewRowDTO> result = new ArrayList<>();
        if (q.getView() == AttendanceReportQuery.View.DETAILS) {
            var visits = details.getReport(q.getStart(), q.getEnd(), null, null, null, null,
                    null, null, null, 0, Integer.MAX_VALUE).getContent();
            for (var detail : visits) {
                Employee employee = employeeMap.get(detail.getEmployeePk());
                if (employee == null) continue;
                AttendanceLog log = sourceById.get(detail.getAttendanceLogId());
                List<AttendanceLog> visit = log == null ? List.of() : List.of(log);
                if (!matchesArea(q.getAreaId(), employee, visit, memberships)) continue;
                Long timetableId = log != null && log.getTimetableId() != null ? log.getTimetableId()
                        : schedules.apply(employee, detail.getDate()).timetableId();
                if (q.getTimetableId() != null && !q.getTimetableId().equals(timetableId)) continue;
                var row = new AttendanceOverviewRowDTO();
                BeanUtils.copyProperties(detail, row);
                row.setStatus(reportStatus(row.getStatus(), row.getDate(), row.getCheckInTime()));
                setTimetable(row, timetableId, timetableMap);
                var detailStatuses = new ArrayList<String>();
                if (row.getStatus() != null) detailStatuses.add(row.getStatus());
                row.setStatuses(detailStatuses);
                result.add(row);
            }
            return result;
        }

        // Never turn future dates or time before hire/after termination into absences.
        LocalDate end = q.getEnd().isAfter(AppTimeZone.today()) ? AppTimeZone.today() : q.getEnd();
        for (Employee employee : selected) {
            LocalDate start = q.getStart();
            if (employee.getHireDate() != null && employee.getHireDate().isAfter(start)) start = employee.getHireDate();
            LocalDate employeeEnd = end;
            if (start.isAfter(employeeEnd)) continue;
            for (var daily : attendance.getEmployeeAttendanceForReport(employee, start, employeeEnd, schedules)) {
                var dayLogs = sourceByDay.getOrDefault(new DayKey(employee.getId(), daily.getDate()), List.of());
                // The legacy schema has no termination date; retain recorded history, do not invent absences.
                if (employee.getEmploymentStatus() == Employee.EmploymentStatus.TERMINATED && dayLogs.isEmpty()
                        && daily.getCheckInTime() == null && (daily.getHoursWorked() == null || daily.getHoursWorked() == 0)) continue;
                if (!matchesArea(q.getAreaId(), employee, dayLogs, memberships)) continue;
                var schedule = schedules.apply(employee, daily.getDate());
                if (q.getTimetableId() != null && !q.getTimetableId().equals(schedule.timetableId())) continue;
                var row = new AttendanceOverviewRowDTO();
                row.setEmployeePk(employee.getId());
                row.setEmployeeId(employee.getEmployeeId());
                row.setFullName(employee.getFirstName() + " " + employee.getLastName());
                row.setFin(employee.getFinNumber());
                row.setDepartment(Optional.ofNullable(departmentMap.get(employee.getDepartmentId()))
                        .map(Department::getDepartmentName).orElse(null));
                row.setPosition(Optional.ofNullable(positionMap.get(employee.getPositionId()))
                        .map(Position::getPositionName).orElse(null));
                row.setArea(areaNames(employee, dayLogs, memberships, branchMap));
                row.setDate(daily.getDate());
                row.setPeriod(daily.getDate().toString());
                row.setPeriodStart(daily.getDate());
                row.setPeriodEnd(daily.getDate());
                row.setCheckInTime(daily.getCheckInTime());
                row.setCheckOutTime(daily.getCheckOutTime());
                row.setWorkedMinutes((int) Math.round(Optional.ofNullable(daily.getHoursWorked()).orElse(0.0) * 60));
                row.setOvertimeMinutes(overtime(daily, schedule, dayLogs));
                row.setLateMinutes(zero(daily.getLateMinutes()));
                row.setEarlyLeaveMinutes(zero(daily.getEarlyLeaveMinutes()));
                row.setPermissionMinutes(zero(daily.getPermissionMinutes()));
                row.setShiftType(daily.getShiftType());
                setTimetable(row, schedule.timetableId(), timetableMap);
                String status = reportStatus(daily.getStatus().name(), daily.getDate(), daily.getCheckInTime());
                // Day-level badges never flow into the per-visit DETAILS path above.
                var displayStatuses = AttendanceDisplayStatus.forDay(daily, inferenceService.inferDay(
                        AttendanceDayAllocation.forDay(dayLogs, daily.getDate(),
                                d -> schedules.apply(employee, d).shiftType()), daily.getDate()));
                if (status.equals("OPEN_SESSION") && displayStatuses.contains(DailyAttendanceSummary.AttendanceStatus.PRESENT)
                        && displayStatuses.contains(DailyAttendanceSummary.AttendanceStatus.MISSING_EXIT)) status = "MISSING_EXIT";
                if (status.equals("ABSENT") && daily.getDate().equals(AppTimeZone.today())
                        && schedule.startTime() != null && AppTimeZone.now().toLocalTime().isBefore(schedule.startTime())) {
                    status = "NOT_STARTED";
                }
                row.setStatus(status);
                var statuses = new LinkedHashSet<String>();
                displayStatuses.stream().map(Enum::name).forEach(statuses::add);
                if (status.equals("NOT_STARTED")) statuses.remove("ABSENT");
                statuses.add(status);
                if (row.getLateMinutes() > 0) statuses.add("LATE");
                if (row.getEarlyLeaveMinutes() > 0) statuses.add("EARLY_LEAVE");
                if (row.getPermissionMinutes() > 0) statuses.add("ON_PERMISSION");
                if (zero(row.getOvertimeMinutes()) > 0) statuses.add("OVERTIME");
                boolean onLeave = approvedLeaves.getOrDefault(employee.getId(), List.of()).stream()
                        .anyMatch(l -> !daily.getDate().isBefore(l.getStartDate()) && !daily.getDate().isAfter(l.getEndDate()));
                if (onLeave) {
                    statuses.add("ON_LEAVE");
                    if (daily.getCheckInTime() != null || !dayLogs.isEmpty()) statuses.add("WORKING_ON_LEAVE");
                }
                row.setStatuses(List.copyOf(statuses));
                row.setAttendedDays(daily.getCheckInTime() != null ? 1 : 0);
                row.setAbsentDays(status.equals("ABSENT") ? 1 : 0);
                row.setLateDays(row.getLateMinutes() > 0 ? 1 : 0);
                row.setEarlyLeaveDays(row.getEarlyLeaveMinutes() > 0 ? 1 : 0);
                row.setLeaveDays(onLeave || status.equals("ON_LEAVE") ? 1 : 0);
                row.setMissingExitDays(statuses.contains("MISSING_EXIT") || statuses.contains("OPEN_SESSION") ? 1 : 0);
                if (q.getView() != AttendanceReportQuery.View.DAILY || matchesStatus(row, q.getStatus())) result.add(row);
            }
        }
        if (q.getView() != AttendanceReportQuery.View.DAILY) result = aggregate(result, q.getView());
        return result.stream().sorted(Comparator.comparing(AttendanceOverviewRowDTO::getPeriod)
                .thenComparing(AttendanceOverviewRowDTO::getFullName)
                .thenComparing(AttendanceOverviewRowDTO::getEmployeePk)).toList();
    }

    private List<AttendanceOverviewRowDTO> aggregate(List<AttendanceOverviewRowDTO> days, AttendanceReportQuery.View view) {
        Map<String, AttendanceOverviewRowDTO> grouped = new LinkedHashMap<>();
        for (var day : days) {
            String period = view == AttendanceReportQuery.View.MONTHLY ? day.getPeriod().substring(0, 7)
                    : day.getPeriod().substring(0, 4);
            String key = day.getEmployeePk() + ":" + period;
            var sum = grouped.get(key);
            if (sum == null) {
                sum = new AttendanceOverviewRowDTO();
                BeanUtils.copyProperties(day, sum);
                sum.setPeriod(period);
                sum.setCheckInTime(null);
                sum.setCheckOutTime(null);
                sum.setStatuses(List.of());
                sum.setStatus(null);
                grouped.put(key, sum);
            } else {
                sum.setPeriodEnd(day.getDate());
                sum.setWorkedMinutes(sum.getWorkedMinutes() + day.getWorkedMinutes());
                if (sum.getOvertimeMinutes() != null || day.getOvertimeMinutes() != null) {
                    sum.setOvertimeMinutes(zero(sum.getOvertimeMinutes()) + zero(day.getOvertimeMinutes()));
                }
                sum.setLateMinutes(sum.getLateMinutes() + day.getLateMinutes());
                sum.setEarlyLeaveMinutes(sum.getEarlyLeaveMinutes() + day.getEarlyLeaveMinutes());
                sum.setPermissionMinutes(sum.getPermissionMinutes() + day.getPermissionMinutes());
                sum.setAttendedDays(sum.getAttendedDays() + day.getAttendedDays());
                sum.setAbsentDays(sum.getAbsentDays() + day.getAbsentDays());
                sum.setLateDays(sum.getLateDays() + day.getLateDays());
                sum.setEarlyLeaveDays(sum.getEarlyLeaveDays() + day.getEarlyLeaveDays());
                sum.setLeaveDays(sum.getLeaveDays() + day.getLeaveDays());
                sum.setMissingExitDays(sum.getMissingExitDays() + day.getMissingExitDays());
                sum.setArea(joinNames(sum.getArea(), day.getArea()));
                sum.setTimetableName(joinNames(sum.getTimetableName(), day.getTimetableName()));
                if (!Objects.equals(sum.getTimetableId(), day.getTimetableId())) sum.setTimetableId(null);
            }
        }
        return new ArrayList<>(grouped.values());
    }

    static boolean matchesStatus(AttendanceOverviewRowDTO row, String status) {
        if (status == null || status.isBlank()) return true;
        return Arrays.stream(status.split(",")).map(String::trim).anyMatch(value -> matchesSingleStatus(row, value));
    }

    private static boolean matchesSingleStatus(AttendanceOverviewRowDTO row, String status) {
        if ("OVERTIME".equals(status)) return zero(row.getOvertimeMinutes()) > 0;
        if ("ON_PERMISSION".equals(status)) return row.getPermissionMinutes() > 0
                || row.getStatuses().contains("ON_PERMISSION") || row.getStatuses().contains("PERMITTED_EARLY_LEAVE");
        if ("MISSING_EXIT".equals(status)) return row.getStatuses().stream()
                .anyMatch(s -> Set.of("OPEN", "OPEN_SESSION", "MISSING_EXIT").contains(s));
        if ("LATE_AND_EARLY".equals(status)) return row.getLateMinutes() > 0 && row.getEarlyLeaveMinutes() > 0;
        if ("WORKDAY_COMPLETE".equals(status)) return row.getStatuses().contains("WORKDAY_COMPLETE")
                || row.getStatuses().contains("PRESENT");
        return row.getStatuses().contains(status);
    }

    private boolean matchesArea(Long area, Employee employee, List<AttendanceLog> visits,
                                Map<Long, List<EmployeeArea>> memberships) {
        if (area == null) return true;
        if (!visits.isEmpty() && visits.stream().allMatch(l -> Boolean.TRUE.equals(l.getAreaSnapshotCaptured()))) {
            return visits.stream().anyMatch(l -> area.equals(l.getAreaSnapshotId()));
        }
        return area.equals(employee.getBranchId()) || memberships.getOrDefault(employee.getId(), List.of())
                .stream().anyMatch(a -> area.equals(a.getBranchId()));
    }

    private String areaNames(Employee employee, List<AttendanceLog> visits, Map<Long, List<EmployeeArea>> memberships,
                             Map<Long, Branch> branchMap) {
        if (!visits.isEmpty() && visits.stream().allMatch(l -> Boolean.TRUE.equals(l.getAreaSnapshotCaptured()))) {
            return visits.stream().map(l -> l.getAreaSnapshotName() == null ? "Ərazi məlum deyil" : l.getAreaSnapshotName())
                    .distinct().sorted().collect(Collectors.joining(", "));
        }
        Set<Long> ids = new HashSet<>();
        ids.add(employee.getBranchId());
        memberships.getOrDefault(employee.getId(), List.of()).forEach(a -> ids.add(a.getBranchId()));
        return ids.stream().map(branchMap::get).filter(Objects::nonNull).map(Branch::getName)
                .sorted().collect(Collectors.joining(", "));
    }

    private void setTimetable(AttendanceOverviewRowDTO row, Long id, Map<Long, Timetable> map) {
        row.setTimetableId(id);
        row.setTimetableName(Optional.ofNullable(map.get(id)).map(Timetable::getName).orElse("Qrafik təyin edilməyib"));
    }

    private void validate(AttendanceReportQuery q) {
        AttendanceReportSort.validate(q);
        if (q.getStart() == null || q.getEnd() == null || q.getStart().isAfter(q.getEnd())) {
            throw new BadRequestException("Başlanğıc tarixi bitmə tarixindən sonra ola bilməz.");
        }
        if (ChronoUnit.DAYS.between(q.getStart(), q.getEnd()) >= 366) {
            throw new BadRequestException("Bir hesabatda ən çox 366 günlük aralıq seçin.");
        }
        if (q.getView() == null || q.getPage() < 0 || q.getSize() < 1 || q.getSize() > 200) {
            throw new BadRequestException("Hesabat bölməsini və səhifə ölçüsünü düzgün seçin (1–200).");
        }
        if (q.getStatus() != null && !q.getStatus().isBlank()) {
            Set<String> allowed = Arrays.stream(DailyAttendanceSummary.AttendanceStatus.values())
                    .map(Enum::name).collect(Collectors.toSet());
            allowed.addAll(Set.of("LATE_AND_EARLY", "NOT_STARTED", "OVERTIME", "WORKING_ON_LEAVE"));
            if (q.getView() != AttendanceReportQuery.View.DAILY || Arrays.stream(q.getStatus().split(",", -1))
                    .map(String::trim).anyMatch(s -> !allowed.contains(s))) {
                throw new BadRequestException("Status filtri yalnız gündəlik hesabat üçün düzgün seçilməlidir.");
            }
        }
    }

    public byte[] export(AttendanceReportQuery query) {
        List<AttendanceOverviewRowDTO> data = AttendanceReportSort.apply(rows(query), query);
        boolean detail = query.getView() == AttendanceReportQuery.View.DETAILS;
        boolean daily = query.getView() == AttendanceReportQuery.View.DAILY;
        List<String> headers = new ArrayList<>(List.of("Dövr", "ID", "Ad soyad", "FIN", "Qrafik", "Ərazi", "Departament", "Vəzifə"));
        if (detail || daily) {
            headers.addAll(List.of("Giriş", "Çıxış", "İş müddəti"));
            if (daily) headers.add("Overtime");
            headers.add("Status");
        } else headers.addAll(List.of("İş müddəti", "Overtime", "Gəldiyi gün", "Gəlmədiyi gün", "Gecikdiyi gün", "Erkən çıxdığı gün", "Məzuniyyət günü", "Çıxışı olmayan gün"));
        if (!detail) headers.addAll(List.of("Gecikmə", "Erkən çıxış", "İcazə"));
        try (Workbook workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Davamiyyət");
            var bold = workbook.createFont(); bold.setBold(true);
            var style = workbook.createCellStyle(); style.setFont(bold);
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) { var cell = header.createCell(i); cell.setCellValue(headers.get(i)); cell.setCellStyle(style); sheet.setColumnWidth(i, 5500); }
            int index = 1;
            for (var dto : data) {
                List<Object> values = new ArrayList<>(Arrays.asList(detail ? dto.getDate() : dto.getPeriod(), dto.getEmployeeId(),
                        dto.getFullName(), dto.getFin(), dto.getTimetableName(), dto.getArea(), dto.getDepartment(), dto.getPosition()));
                if (detail || daily) {
                    values.addAll(Arrays.asList(dateTime(dto.getCheckInTime()), dateTime(dto.getCheckOutTime()), duration(dto.getWorkedMinutes())));
                    if (daily) values.add(duration(dto.getOvertimeMinutes()));
                    values.add(dto.getStatuses().stream().map(AttendanceOverviewService::statusLabel).distinct().collect(Collectors.joining(", ")));
                } else values.addAll(List.of(duration(dto.getWorkedMinutes()), duration(dto.getOvertimeMinutes()), dto.getAttendedDays(), dto.getAbsentDays(),
                        dto.getLateDays(), dto.getEarlyLeaveDays(), dto.getLeaveDays(), dto.getMissingExitDays()));
                if (!detail) values.addAll(List.of(duration(dto.getLateMinutes()), duration(dto.getEarlyLeaveMinutes()), duration(dto.getPermissionMinutes())));
                Row row = sheet.createRow(index++);
                for (int i = 0; i < values.size(); i++) {
                    var cell = row.createCell(i); Object value = values.get(i);
                    if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(value == null ? "" : value.toString());
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(data.size(), 0), 0, headers.size() - 1));
            workbook.write(output); return output.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException("Excel faylı hazırlanmadı", e); }
    }

    private static String reportStatus(String status, LocalDate date, OffsetDateTime entry) {
        // Display only: keep expired calendar days out of the live-presence label.
        if ("OPEN".equals(status) || "OPEN_SESSION".equals(status) || "MISSING_EXIT".equals(status)) {
            return entry != null && AppTimeZone.today().equals(date) ? "OPEN_SESSION" : "MISSING_EXIT";
        }
        return status;
    }

    public static String statusLabel(String value) {
        if (value == null) return "";
        return switch (value) {
            case "LATE" -> "Gecikib"; case "EARLY_LEAVE" -> "Erkən çıxıb";
            case "ABSENT" -> "Gəlməyib"; case "ON_LEAVE" -> "Məzuniyyət";
            case "WORKING_ON_LEAVE" -> "Həm işdə, həm məzuniyyətdə";
            case "DAY_OFF" -> "İstirahət";
            case "OPEN", "MISSING_EXIT" -> "Çıxış yoxdur";
            case "OPEN_SESSION" -> "İşdə";
            case "ON_PERMISSION", "PERMITTED_EARLY_LEAVE" -> "İcazə";
            case "NOT_STARTED" -> "Başlamayıb"; case "OVERTIME" -> "Overtime";
            case "MANUALLY_CORRECTED" -> "Düzəliş";
            case "CLOSED", "WORKDAY_COMPLETE", "PRESENT" -> "İşdə olub"; default -> value;
        };
    }
    private static String dateTime(OffsetDateTime value) { return value == null ? "" : value.atZoneSameInstant(AppTimeZone.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }
    // Use the same source-visit overtime as MCG Tabel without changing daily worked time.
    private Integer overtime(EmployeeAttendanceRowDTO day, AttendanceScheduleResolver.DaySchedule schedule,
                             List<AttendanceLog> dayLogs) {
        if (schedule.flexible() || schedule.workingDay() && (schedule.startTime() == null || schedule.endTime() == null)) return null;
        int actualMinutes = (int) Math.round(Optional.ofNullable(day.getHoursWorked()).orElse(0.0) * 60)
                - zero(day.getCreditedPermissionMinutes());
        return McgTabelCalculator.overtimeAfterShift(day.getDate(),
                inferenceService.inferDay(dayLogs, day.getDate()), schedule, Math.max(actualMinutes, 0));
    }
    private static String duration(Integer value) { return value == null ? "—" : "%d saat %d dəqiqə".formatted(value / 60, value % 60); }
    private static int zero(Integer value) { return value == null ? 0 : value; }
    private static String joinNames(String a, String b) { return java.util.stream.Stream.of(a, b).filter(Objects::nonNull).flatMap(s -> Arrays.stream(s.split(", "))).distinct().sorted().collect(Collectors.joining(", ")); }
    private static boolean contains(String text, String part) { return part == null || part.isBlank() || text != null && text.toLowerCase(Locale.forLanguageTag("az")).contains(part.trim().toLowerCase(Locale.forLanguageTag("az"))); }
    private static <T> Map<Long, T> index(List<T> values, Function<T, Long> id) { return values.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a)); }
    private record DayKey(Long employee, LocalDate date) {}
}
