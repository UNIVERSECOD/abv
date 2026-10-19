package com.hic.service;

import com.hic.dto.TabelMonthlyDTO;
import com.hic.dto.TabelMonthlyDTO.CalculationMode;
import com.hic.dto.TabelMonthlyDTO.McgDayDTO;
import com.hic.exception.BadRequestException;
import com.hic.model.DailyAttendanceSummary;
import com.hic.model.Employee;
import com.hic.model.EmployeePermission;
import com.hic.model.HolidayPermission;
import com.hic.model.LeaveRequest;
import com.hic.model.Position;
import com.hic.repository.DailyAttendanceSummaryRepository;
import com.hic.repository.EmployeeAreaRepository;
import com.hic.repository.EmployeePermissionRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.HolidayPermissionRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.PositionRepository;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TabelService {

    private static final String QI_CODE = "Q/I";

    private final EmployeeRepository employeeRepository;
    private final EmployeeAreaRepository employeeAreaRepository;
    private final PositionRepository positionRepository;
    private final DailyAttendanceSummaryRepository dailyAttendanceSummaryRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeePermissionRepository employeePermissionRepository;
    private final HolidayPermissionRepository holidayPermissionRepository;
    private final AttendanceScheduleResolver attendanceScheduleResolver;
    private final McgTabelProjectionService mcgProjectionService;

    public TabelMonthlyDTO getMonthlyTabel(int year,
                                           int month,
                                           Long branchId,
                                           Long departmentId,
                                           Long positionId,
                                           String search) {
        return getMonthlyTabel(year, month, branchId, departmentId, positionId, search, CalculationMode.STANDARD);
    }

    public TabelMonthlyDTO getMonthlyTabel(int year, int month, Long branchId, Long departmentId,
                                           Long positionId, String search, CalculationMode mode) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("Ay 1 ilə 12 arasında olmalıdır.");
        }
        if (year < 1 || year > 9999) {
            throw new BadRequestException("İl 1 ilə 9999 arasında olmalıdır.");
        }
        Long tenantId = requireTenantId();

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();

        Set<Long> areaEmployeeIds = branchId == null
                ? Set.of()
                : employeeAreaRepository.findByBranchId(branchId).stream()
                .filter(membership -> tenantId.equals(membership.getTenantId()))
                .map(com.hic.model.EmployeeArea::getEmployeeId)
                .collect(java.util.stream.Collectors.toSet());

        List<Employee> employees = employeeRepository.findByTenantId(tenantId, Pageable.unpaged()).getContent()
                .stream()
                .filter(employee -> branchId == null
                        || branchId.equals(employee.getBranchId())
                        || areaEmployeeIds.contains(employee.getId()))
                .filter(employee -> departmentId == null || departmentId.equals(employee.getDepartmentId()))
                .filter(employee -> positionId == null || positionId.equals(employee.getPositionId()))
                .filter(employee -> matchesSearch(employee, search))
                .toList();

        Map<Long, String> positionNames = new HashMap<>();
        for (Position position : positionRepository.findByTenantId(tenantId)) {
            positionNames.put(position.getId(), position.getPositionName());
        }

        Map<Long, Set<LocalDate>> leaveByEmployee = buildLeaveDaysByEmployee(tenantId, employees, start, end);
        Map<Long, Set<LocalDate>> vacationByEmployee = new HashMap<>();
        if (!employees.isEmpty()) {
            for (LeaveRequest leave : leaveRequestRepository.findApprovedByTenantAndEmployeeIdsAndDateRange(
                    tenantId, employees.stream().map(Employee::getId).toList(), start, end)) {
                addDateRange(vacationByEmployee, leave.getEmployeeId(), leave.getStartDate(), leave.getEndDate(), start, end);
            }
        }
        Map<Long, Set<LocalDate>> holidayByEmployee = buildHolidayDaysByEmployee(tenantId, employees, start, end);
        boolean mcg = mode == CalculationMode.MCG;
        var projector = mcg ? mcgProjectionService.forMonth(tenantId, employees, start, end) : null;

        List<TabelMonthlyDTO.RowDTO> rows = new ArrayList<>();
        for (Employee employee : employees) {
            List<DailyAttendanceSummary> summaries = dailyAttendanceSummaryRepository
                    .findByEmployeeIdAndAttendanceDateBetween(employee.getId(), start, end);

            Map<LocalDate, DailyAttendanceSummary> summariesByDate = new HashMap<>();
            for (DailyAttendanceSummary summary : summaries) {
                summariesByDate.put(summary.getAttendanceDate(), summary);
            }

            LinkedHashMap<Integer, Object> daily = new LinkedHashMap<>();
            LinkedHashMap<Integer, McgDayDTO> mcgDaily = new LinkedHashMap<>();
            int workingDays = 0;
            double totalHours = 0.0;
            int fullDays = 0;
            int partialMinutes = 0;
            int overtimeMinutes = 0;
            int totalMinutes = 0;

            Set<LocalDate> leaveDates = leaveByEmployee.getOrDefault(employee.getId(), Set.of());
            Set<LocalDate> holidayDates = holidayByEmployee.getOrDefault(employee.getId(), Set.of());

            for (int day = 1; day <= yearMonth.lengthOfMonth(); day++) {
                LocalDate date = yearMonth.atDay(day);
                Object value;

                if (vacationByEmployee.getOrDefault(employee.getId(), Set.of()).contains(date)) {
                    value = "M";
                } else if (holidayDates.contains(date) || leaveDates.contains(date)) {
                    value = mcg ? "Q/İ" : QI_CODE;
                } else {
                    DailyAttendanceSummary summary = summariesByDate.get(date);
                    double hours = summary != null && summary.getHoursWorked() != null ? summary.getHoursWorked() : 0.0;
                    McgDayDTO mcgDay = mcg ? projector.project(employee, date, summary) : null;
                    if (mcgDay != null) {
                        mcgDaily.put(day, mcgDay);
                        hours = mcgDay.workedMinutes() / 60.0;
                        totalMinutes += mcgDay.workedMinutes();
                        overtimeMinutes += mcgDay.overtimeMinutes();
                        if (mcgDay.fullDay()) fullDays++;
                        else partialMinutes += mcgDay.regularMinutes();
                    }
                    if (hours > 0) {
                        double normalized = round2(hours);
                        value = normalized;
                        workingDays++;
                        totalHours += normalized;
                    } else if (mcgDay != null) {
                        value = "FUTURE".equals(mcgDay.status()) || "DAY_OFF".equals(mcgDay.status()) ? null : 0;
                    } else if (!isScheduledWorkingDay(employee, date, summary)) {
                        value = null;
                    } else {
                        value = 0;
                    }
                    if (mcgDay != null && mcgDay.scheduledDayOff()) value = "Q/İ";
                }

                daily.put(day, value);
            }

            TabelMonthlyDTO.RowDTO row = new TabelMonthlyDTO.RowDTO();
            row.setEmployeePk(employee.getId());
            row.setFin(employee.getFinNumber());
            row.setFullName(buildFullName(employee));
            row.setPosition(positionNames.getOrDefault(employee.getPositionId(), "-"));
            row.setEmploymentStatus(employee.getEmploymentStatus());
            row.setDaily(daily);
            row.setWorkingDays(workingDays);
            row.setTotalHours(round2(mcg ? totalMinutes / 60.0 : totalHours));
            if (mcg) {
                row.setMcgDaily(mcgDaily);
                row.setFullDays(fullDays);
                row.setPartialHours(round2(partialMinutes / 60.0));
                row.setOvertimeHours(round2(overtimeMinutes / 60.0));
            }
            rows.add(row);
        }

        TabelMonthlyDTO dto = new TabelMonthlyDTO();
        dto.setCalculationMode(mcg ? CalculationMode.MCG : CalculationMode.STANDARD);
        dto.setYear(year);
        dto.setMonth(month);
        dto.setDaysInMonth(yearMonth.lengthOfMonth());
        dto.setEmployees(rows.size());
        dto.setRows(rows);
        return dto;
    }

    public byte[] exportMonthlyTabel(int year,
                                     int month,
                                     Long branchId,
                                     Long departmentId,
                                     Long positionId,
                                     String search) {
        return exportMonthlyTabel(year, month, branchId, departmentId, positionId, search, CalculationMode.STANDARD);
    }

    public byte[] exportMonthlyTabel(int year, int month, Long branchId, Long departmentId,
                                     Long positionId, String search, CalculationMode mode) {
        TabelMonthlyDTO tabel = getMonthlyTabel(year, month, branchId, departmentId, positionId, search, mode);
        boolean mcg = tabel.getCalculationMode() == CalculationMode.MCG;

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Tabel");
            YearMonth yearMonth = YearMonth.of(year, month);
            Font terminatedFont = workbook.createFont();
            terminatedFont.setColor(IndexedColors.RED.getIndex());
            terminatedFont.setBold(true);
            CellStyle terminatedNameStyle = workbook.createCellStyle();
            terminatedNameStyle.setFont(terminatedFont);
            CellStyle ordinaryDayStyle = mcgDayStyle(workbook, IndexedColors.BLACK, "0");
            CellStyle ordinaryHoursStyle = mcgDayStyle(workbook, IndexedColors.BLACK, "[hh]:mm");
            CellStyle fullDayStyle = mcgDayStyle(workbook, IndexedColors.DARK_GREEN, "0");
            CellStyle overtimeDayStyle = mcgDayStyle(workbook, IndexedColors.BLUE, "[hh]:mm");
            CellStyle shortDayStyle = mcgDayStyle(workbook, IndexedColors.RED, "[hh]:mm");

            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue((mcg ? "Attendra MCG — Tabel: " : "Tabel arxivi: ")
                    + yearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + " " + year);

            int column = 0;
            Row header = sheet.createRow(1);
            header.createCell(column++).setCellValue("s/s");
            header.createCell(column++).setCellValue("FIN kod");
            header.createCell(column++).setCellValue("Soyadı, adı, atasının adı");
            header.createCell(column++).setCellValue("Vəzifəsi");
            for (int day = 1; day <= tabel.getDaysInMonth(); day++) {
                header.createCell(column++).setCellValue(day);
            }
            if (mcg) {
                header.createCell(column++).setCellValue("Overtime (saat)");
            }
            header.createCell(column++).setCellValue("İş günlərinin sayı");
            header.createCell(column).setCellValue("İş saatlarının cəmi");

            int rowIndex = 2;
            int order = 1;
            for (TabelMonthlyDTO.RowDTO row : tabel.getRows()) {
                Row sheetRow = sheet.createRow(rowIndex++);
                int dataCol = 0;
                sheetRow.createCell(dataCol++).setCellValue(order++);
                sheetRow.createCell(dataCol++).setCellValue(row.getFin() == null ? "-" : row.getFin());
                var nameCell = sheetRow.createCell(dataCol++);
                nameCell.setCellValue(row.getFullName());
                if (Employee.EmploymentStatus.TERMINATED.equals(row.getEmploymentStatus())) {
                    nameCell.setCellStyle(terminatedNameStyle);
                }
                sheetRow.createCell(dataCol++).setCellValue(row.getPosition());

                for (int day = 1; day <= tabel.getDaysInMonth(); day++) {
                    Object value = row.getDaily().get(day);
                    var cell = sheetRow.createCell(dataCol++);
                    if (mcg) cell.setCellStyle(ordinaryDayStyle);
                    if (value == null) {
                        if (mcg) cell.setCellValue(0);
                        else cell.setCellValue("");
                    } else if (mcg && value instanceof Number && row.getMcgDaily().containsKey(day)) {
                        var mcgDay = row.getMcgDaily().get(day);
                        cell.setCellValue(mcgDay.fullDay() ? 1 : mcgDay.workedMinutes() / 1440.0);
                        if (mcgDay.workedMinutes() > 0) cell.setCellStyle(ordinaryHoursStyle);
                        if (mcgDay.overtimeMinutes() > 0) cell.setCellStyle(overtimeDayStyle);
                        else if (mcgDay.fullDay()) cell.setCellStyle(fullDayStyle);
                        else if (mcgDay.sourceVerified() && mcgDay.workedMinutes() > 0
                                && mcgDay.workedMinutes() < mcgDay.expectedMinutes()) {
                            cell.setCellStyle(shortDayStyle);
                        }
                    } else if (value instanceof Number number) {
                        cell.setCellValue(mcg ? number.doubleValue() / 24.0 : number.doubleValue());
                        if (mcg && number.doubleValue() > 0) cell.setCellStyle(ordinaryHoursStyle);
                    } else {
                        cell.setCellValue(String.valueOf(value));
                    }
                }

                if (mcg) {
                    sheetRow.setHeightInPoints(32);
                    var overtimeCell = sheetRow.createCell(dataCol++);
                    overtimeCell.setCellValue(Math.round(row.getOvertimeHours() * 60) / 1440.0);
                    overtimeCell.setCellStyle(row.getOvertimeHours() > 0 ? overtimeDayStyle : ordinaryHoursStyle);
                }
                sheetRow.createCell(dataCol++).setCellValue(row.getWorkingDays());
                var totalCell = sheetRow.createCell(dataCol);
                totalCell.setCellValue(mcg ? Math.round(row.getTotalHours() * 60) / 1440.0 : row.getTotalHours());
                if (mcg) totalCell.setCellStyle(ordinaryHoursStyle);
            }

            for (int i = 0; i <= 5; i++) {
                sheet.autoSizeColumn(i);
            }
            if (mcg) {
                sheet.setColumnWidth(0, 6 * 256);
                sheet.setColumnWidth(1, 14 * 256);
                sheet.setColumnWidth(2, 32 * 256);
                sheet.setColumnWidth(3, 22 * 256);
                for (int i = 4; i < 4 + tabel.getDaysInMonth(); i++) sheet.setColumnWidth(i, 7 * 256);
                for (int i = 4 + tabel.getDaysInMonth(); i <= column; i++) sheet.setColumnWidth(i, 20 * 256);
                Font headerFont = workbook.createFont();
                headerFont.setBold(true);
                CellStyle headerStyle = workbook.createCellStyle();
                headerStyle.setFont(headerFont);
                headerStyle.setWrapText(true);
                for (var cell : header) cell.setCellStyle(headerStyle);
                header.setHeightInPoints(45);
                sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, column));
                sheet.createFreezePane(4, 2);
                sheet.createRow(++rowIndex).createCell(0).setCellValue(
                        "1 = həmin günün qrafiki tam yerinə yetirilib (tolerans və iş saatından çıxılmayan təsdiqli icazə daxil).");
                sheet.createRow(++rowIndex).createCell(0).setCellValue(
                        "Tam gün şərtləri ödənirsə və overtime 1 saata çatmayıbsa 1 göstərilir; digər iş müddətləri saat:dəqiqə ilədir. İstirahət günü = Q/İ. M = təsdiqlənmiş məzuniyyət.");
                sheet.createRow(++rowIndex).createCell(0).setCellValue(
                        "Qrafik bitdikdən sonrakı faktiki giriş–çıxış müddətləri 60 dəqiqəyə çatdıqda overtime sayılır; aradakı boşluqlar sayılmır. Göy = overtime, qırmızı = az iş, yaşıl 1 = norma, qara = digər.");
                sheet.createRow(++rowIndex).createCell(0).setCellValue(
                        "Müddətlər saat:dəqiqə ilədir. Overtime cəmi iş vaxtının daxilindədir, üstünə yenidən əlavə edilmir. Qırmızı adla işarələnən əməkdaşlar işdən azad edilib.");
                for (int i = rowIndex - 3; i <= rowIndex; i++) {
                    sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(i, i, 0, column));
                }
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to export tabel", ex);
        }
    }

    private CellStyle mcgDayStyle(Workbook workbook, IndexedColors color, String numberFormat) {
        Font font = workbook.createFont();
        font.setColor(color.getIndex());
        font.setBold(color != IndexedColors.BLACK);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setDataFormat(workbook.createDataFormat().getFormat(numberFormat));
        return style;
    }

    private Map<Long, Set<LocalDate>> buildLeaveDaysByEmployee(Long tenantId,
                                                                List<Employee> employees,
                                                                LocalDate start,
                                                                LocalDate end) {
        Map<Long, Set<LocalDate>> result = new HashMap<>();
        if (employees.isEmpty()) {
            return result;
        }

        List<Long> employeeIds = employees.stream().map(Employee::getId).toList();

        List<EmployeePermission> employeePermissions = employeePermissionRepository.findByDateRange(tenantId, start, end);
        Set<Long> employeeIdSet = new HashSet<>(employeeIds);
        for (EmployeePermission permission : employeePermissions) {
            if (permission.getEmployeeId() == null || !employeeIdSet.contains(permission.getEmployeeId())) {
                continue;
            }
            String status = permission.getStatus() == null ? "" : permission.getStatus().name();
            if (!"ACTIVE".equals(status) && !"APPROVED".equals(status)) {
                continue;
            }
            if (!permission.isFullDay() || !Boolean.TRUE.equals(permission.getDeductFromWorkHours())) {
                continue;
            }
            addDateRange(result, permission.getEmployeeId(), permission.getStartDate(), permission.getEndDate(), start, end);
        }

        return result;
    }

    private Map<Long, Set<LocalDate>> buildHolidayDaysByEmployee(Long tenantId,
                                                                  List<Employee> employees,
                                                                  LocalDate start,
                                                                  LocalDate end) {
        Map<Long, Set<LocalDate>> result = new HashMap<>();
        if (employees.isEmpty()) {
            return result;
        }

        List<HolidayPermission> holidays = holidayPermissionRepository.findOverlapping(tenantId, start, end);
        for (HolidayPermission holiday : holidays) {
            String status = holiday.getStatus() == null ? "" : holiday.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!"ACTIVE".equals(status)) {
                continue;
            }

            for (Employee employee : employees) {
                if (!isHolidayForEmployee(holiday, employee)) {
                    continue;
                }
                addDateRange(result, employee.getId(), holiday.getStartDate(), holiday.getEndDate(), start, end);
            }
        }

        return result;
    }

    private boolean isHolidayForEmployee(HolidayPermission holiday, Employee employee) {
        String scope = holiday.getApplyScope() == null
                ? "COMPANY"
                : holiday.getApplyScope().trim().toUpperCase(Locale.ROOT);

        return switch (scope) {
            case "COMPANY" -> true;
            case "BRANCH" -> contains(holiday.getTargetIds(), employee.getBranchId());
            case "DEPARTMENT" -> contains(holiday.getTargetIds(), employee.getDepartmentId());
            case "EMPLOYEE" -> contains(holiday.getEmployeeIds(), employee.getId());
            default -> false;
        };
    }

    private void addDateRange(Map<Long, Set<LocalDate>> map,
                              Long employeeId,
                              LocalDate start,
                              LocalDate end,
                              LocalDate clampStart,
                              LocalDate clampEnd) {
        if (employeeId == null || start == null || end == null) {
            return;
        }

        LocalDate from = start.isBefore(clampStart) ? clampStart : start;
        LocalDate to = end.isAfter(clampEnd) ? clampEnd : end;
        if (to.isBefore(from)) {
            return;
        }

        Set<LocalDate> dates = map.computeIfAbsent(employeeId, key -> new HashSet<>());
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            dates.add(cursor);
            cursor = cursor.plusDays(1);
        }
    }

    private boolean matchesSearch(Employee employee, String search) {
        if (!StringUtils.hasText(search)) {
            return true;
        }

        String value = search.trim().toLowerCase(Locale.ROOT);
        String fullName = (employee.getLastName() + " " + employee.getFirstName() + " "
                + (employee.getFatherName() == null ? "" : employee.getFatherName())).toLowerCase(Locale.ROOT);

        return containsIgnoreCase(employee.getFinNumber(), value)
                || containsIgnoreCase(employee.getEmployeeId(), value)
                || fullName.contains(value);
    }

    private boolean containsIgnoreCase(String source, String search) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(search);
    }

    private boolean contains(Long[] values, Long value) {
        if (values == null || value == null) {
            return false;
        }
        return Arrays.stream(values).anyMatch(value::equals);
    }

    private String buildFullName(Employee employee) {
        String fatherName = employee.getFatherName() == null ? "" : " " + employee.getFatherName();
        return employee.getLastName() + " " + employee.getFirstName() + fatherName;
    }

    private boolean isScheduledWorkingDay(
            Employee employee,
            LocalDate date,
            DailyAttendanceSummary summary
    ) {
        if (summary != null && summary.getIsStandardDay() != null) {
            return summary.getIsStandardDay();
        }
        return attendanceScheduleResolver.resolve(employee, date).workingDay();
    }

    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is missing");
        }
        return tenantId;
    }
}
