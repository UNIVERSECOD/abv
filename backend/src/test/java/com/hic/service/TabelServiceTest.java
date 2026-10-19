package com.hic.service;

import com.hic.dto.TabelMonthlyDTO;
import com.hic.model.DailyAttendanceSummary;
import com.hic.model.Employee;
import com.hic.model.EmployeeArea;
import com.hic.model.EmployeePermission;
import com.hic.model.HolidayPermission;
import com.hic.model.LeaveRequest;
import com.hic.model.Position;
import com.hic.repository.DailyAttendanceSummaryRepository;
import com.hic.repository.EmployeePermissionRepository;
import com.hic.repository.EmployeeAreaRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.repository.HolidayPermissionRepository;
import com.hic.repository.LeaveRequestRepository;
import com.hic.repository.PositionRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TabelServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private EmployeeAreaRepository employeeAreaRepository;
    @Mock
    private PositionRepository positionRepository;
    @Mock
    private DailyAttendanceSummaryRepository dailyAttendanceSummaryRepository;
    @Mock
    private LeaveRequestRepository leaveRequestRepository;
    @Mock
    private EmployeePermissionRepository employeePermissionRepository;
    @Mock
    private HolidayPermissionRepository holidayPermissionRepository;
    @Mock
    private AttendanceScheduleResolver attendanceScheduleResolver;
    @Mock
    private McgTabelProjectionService mcgProjectionService;

    @InjectMocks
    private TabelService tabelService;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void vacationOverridesRestDaysAcrossMonthBoundaryAndExportsMWithoutAddingWork() throws Exception {
        TenantContext.setTenantId(7L);
        var employee = new Employee(); employee.setId(11L); employee.setTenantId(7L);
        employee.setFirstName("Test"); employee.setLastName("Vacation");
        var vacation = new LeaveRequest(); vacation.setEmployeeId(11L);
        vacation.setStartDate(LocalDate.of(2026, 3, 30)); vacation.setEndDate(LocalDate.of(2026, 4, 2));
        when(employeeRepository.findByTenantId(7L, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(employee)));
        when(leaveRequestRepository.findApprovedByTenantAndEmployeeIdsAndDateRange(
                7L, List.of(11L), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))).thenReturn(List.of(vacation));
        when(mcgProjectionService.forMonth(any(), any(), any(), any())).thenReturn((e, date, summary) ->
                new TabelMonthlyDTO.McgDayDTO(false, 0, 0, 0, 0, 0, "DAY_OFF", true, 0, true));
        var data = tabelService.getMonthlyTabel(2026, 4, null, null, null, null, TabelMonthlyDTO.CalculationMode.MCG);
        var row = data.getRows().get(0);
        assertEquals("M", row.getDaily().get(1));
        assertEquals("M", row.getDaily().get(2));
        assertEquals("Q/İ", row.getDaily().get(3));
        assertEquals(0, row.getWorkingDays());
        assertEquals(0.0, row.getTotalHours());
        assertEquals(0.0, row.getOvertimeHours());
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(tabelService.exportMonthlyTabel(
                2026, 4, null, null, null, null, TabelMonthlyDTO.CalculationMode.MCG)))) {
            assertEquals("M", book.getSheetAt(0).getRow(2).getCell(4).getStringCellValue());
            assertEquals("M", book.getSheetAt(0).getRow(2).getCell(5).getStringCellValue());
            assertEquals("Q/İ", book.getSheetAt(0).getRow(2).getCell(6).getStringCellValue());
        }
    }

    @Test
    void getMonthlyTabel_computesDailyValuesAndTotals() {
        TenantContext.setTenantId(7L);

        Employee employee = new Employee();
        employee.setId(11L);
        employee.setBranchId(3L);
        employee.setDepartmentId(4L);
        employee.setPositionId(5L);
        employee.setFirstName("Ayla");
        employee.setLastName("Aliyeva");
        employee.setFatherName("Rauf");
        employee.setFinNumber("FIN123");

        Position position = new Position();
        position.setId(5L);
        position.setPositionName("Mütəxəssis");

        DailyAttendanceSummary day3 = new DailyAttendanceSummary();
        day3.setAttendanceDate(LocalDate.of(2026, 4, 3));
        day3.setHoursWorked(9.0);

        DailyAttendanceSummary day4 = new DailyAttendanceSummary();
        day4.setAttendanceDate(LocalDate.of(2026, 4, 4));
        day4.setHoursWorked(8.5);

        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setEmployeeId(11L);
        leaveRequest.setStartDate(LocalDate.of(2026, 4, 5));
        leaveRequest.setEndDate(LocalDate.of(2026, 4, 5));

        HolidayPermission holiday = new HolidayPermission();
        holiday.setApplyScope("COMPANY");
        holiday.setStatus("ACTIVE");
        holiday.setStartDate(LocalDate.of(2026, 4, 6));
        holiday.setEndDate(LocalDate.of(2026, 4, 6));

        EmployeePermission creditedFullDay = new EmployeePermission();
        creditedFullDay.setEmployeeId(11L);
        creditedFullDay.setStartDate(LocalDate.of(2026, 4, 3));
        creditedFullDay.setEndDate(LocalDate.of(2026, 4, 3));
        creditedFullDay.setDeductFromWorkHours(false);
        creditedFullDay.setStatus(EmployeePermission.Status.APPROVED);

        EmployeePermission deductedHourly = new EmployeePermission();
        deductedHourly.setEmployeeId(11L);
        deductedHourly.setStartDate(LocalDate.of(2026, 4, 4));
        deductedHourly.setEndDate(LocalDate.of(2026, 4, 4));
        deductedHourly.setStartTime(LocalTime.of(15, 0));
        deductedHourly.setEndTime(LocalTime.of(17, 0));
        deductedHourly.setDeductFromWorkHours(true);
        deductedHourly.setStatus(EmployeePermission.Status.APPROVED);

        EmployeePermission deductedFullDay = new EmployeePermission();
        deductedFullDay.setEmployeeId(11L);
        deductedFullDay.setStartDate(LocalDate.of(2026, 4, 7));
        deductedFullDay.setEndDate(LocalDate.of(2026, 4, 7));
        deductedFullDay.setDeductFromWorkHours(true);
        deductedFullDay.setStatus(EmployeePermission.Status.APPROVED);

        when(employeeRepository.findByTenantId(7L, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(employee)));
        when(positionRepository.findByTenantId(7L)).thenReturn(List.of(position));
        when(leaveRequestRepository.findApprovedByTenantAndEmployeeIdsAndDateRange(any(), any(), any(), any()))
                .thenReturn(List.of(leaveRequest));
        when(employeePermissionRepository.findByDateRange(any(), any(), any()))
                .thenReturn(List.of(creditedFullDay, deductedHourly, deductedFullDay));
        when(holidayPermissionRepository.findOverlapping(any(), any(), any())).thenReturn(List.of(holiday));
        when(dailyAttendanceSummaryRepository.findByEmployeeIdAndAttendanceDateBetween(11L,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
                .thenReturn(List.of(day3, day4));
        when(attendanceScheduleResolver.resolve(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            boolean workingDay = date.getDayOfWeek().getValue() <= 5;
            return new AttendanceScheduleResolver.DaySchedule(
                    1L, "STANDARD", workingDay,
                    workingDay ? LocalTime.of(8, 0) : null,
                    workingDay ? LocalTime.of(17, 0) : null,
                    60, 30, 30);
        });

        TabelMonthlyDTO result = tabelService.getMonthlyTabel(2026, 4, 3L, 4L, 5L, "FIN123");

        assertEquals(30, result.getDaysInMonth());
        assertEquals(1, result.getEmployees());
        assertEquals(1, result.getRows().size());

        TabelMonthlyDTO.RowDTO row = result.getRows().get(0);
        assertEquals("Aliyeva Ayla Rauf", row.getFullName());
        assertEquals("Mütəxəssis", row.getPosition());
        assertEquals(0, row.getDaily().get(1));
        assertEquals(9.0, row.getDaily().get(3));
        assertEquals(8.5, row.getDaily().get(4));
        assertEquals("M", row.getDaily().get(5));
        assertEquals("Q/I", row.getDaily().get(6));
        assertEquals("Q/I", row.getDaily().get(7));
        assertNull(row.getDaily().get(11));
        assertEquals(2, row.getWorkingDays());
        assertEquals(17.5, row.getTotalHours());
        assertEquals(TabelMonthlyDTO.CalculationMode.STANDARD, result.getCalculationMode());
        org.mockito.Mockito.verifyNoInteractions(mcgProjectionService);
    }

    @Test
    void mcgExcelShowsPreciseHoursColorsAndRestDaysWithThreeMonthlyTotals() throws Exception {
        TenantContext.setTenantId(7L);
        var employee = new Employee(); employee.setId(11L); employee.setTenantId(7L);
        employee.setFirstName("Test"); employee.setLastName("MCG");
        employee.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);
        when(employeeRepository.findByTenantId(7L, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(employee)));
        when(mcgProjectionService.forMonth(any(), any(), any(), any())).thenReturn((e, date, summary) -> switch (date.getDayOfMonth()) {
            case 1 -> new TabelMonthlyDTO.McgDayDTO(true, 480, 480, 0, 0, 0, "PRESENT", true, 480, false);
            case 2 -> new TabelMonthlyDTO.McgDayDTO(false, 496, 150, 346, 0, 0, "LATE", true, 420, false);
            case 3 -> new TabelMonthlyDTO.McgDayDTO(false, 480, 480, 0, 0, 0, "SUMMARY_ONLY", false, 480, false);
            case 4 -> new TabelMonthlyDTO.McgDayDTO(false, 420, 420, 0, 0, 0, "EARLY_LEAVE", true, 480, false);
            case 5 -> new TabelMonthlyDTO.McgDayDTO(true, 539, 539, 0, 0, 0, "PRESENT", true, 480, false);
            case 6 -> new TabelMonthlyDTO.McgDayDTO(false, 120, 120, 0, 0, 0, "PRESENT", true, 0, true);
            case 7 -> new TabelMonthlyDTO.McgDayDTO(false, 0, 0, 0, 0, 0, "ABSENT", true, 480, false);
            default -> new TabelMonthlyDTO.McgDayDTO(false, 0, 0, 0, 0, 0, "DAY_OFF", true, 0, true);
        });
        var data = tabelService.getMonthlyTabel(2026, 4, null, null, null, null, TabelMonthlyDTO.CalculationMode.MCG);
        var row = data.getRows().get(0);
        assertEquals(2, row.getFullDays()); assertEquals(19.5, row.getPartialHours());
        assertEquals(5.77, row.getOvertimeHours()); assertEquals(42.25, row.getTotalHours());
        assertEquals(6, row.getWorkingDays()); assertEquals(8.0, row.getDaily().get(1));
        assertEquals("Q/İ", row.getDaily().get(6));
        byte[] excel = tabelService.exportMonthlyTabel(2026, 4, null, null, null, null, TabelMonthlyDTO.CalculationMode.MCG);
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(excel))) {
            var sheet = book.getSheet("Tabel");
            assertEquals(37, sheet.getRow(1).getLastCellNum());
            assertEquals("Overtime (saat)", sheet.getRow(1).getCell(34).getStringCellValue());
            assertEquals("İş günlərinin sayı", sheet.getRow(1).getCell(35).getStringCellValue());
            assertEquals("İş saatlarının cəmi", sheet.getRow(1).getCell(36).getStringCellValue());
            assertEquals(6 * 256, sheet.getColumnWidth(0));
            assertEquals(20 * 256, sheet.getColumnWidth(35));
            assertEquals(true, sheet.getRow(1).getCell(35).getCellStyle().getWrapText());
            assertEquals(1.0, sheet.getRow(2).getCell(4).getNumericCellValue());
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
            assertEquals(496 / 1440.0, sheet.getRow(2).getCell(5).getNumericCellValue());
            assertEquals("08:16", formatter.formatCellValue(sheet.getRow(2).getCell(5)));
            assertEquals("08:00", formatter.formatCellValue(sheet.getRow(2).getCell(6)));
            assertEquals("07:00", formatter.formatCellValue(sheet.getRow(2).getCell(7)));
            assertEquals("1", formatter.formatCellValue(sheet.getRow(2).getCell(8)));
            assertEquals("Q/İ", sheet.getRow(2).getCell(9).getStringCellValue());
            assertEquals(0.0, sheet.getRow(2).getCell(10).getNumericCellValue());
            short[] dayColors = {IndexedColors.DARK_GREEN.getIndex(), IndexedColors.BLUE.getIndex(),
                    IndexedColors.BLACK.getIndex(), IndexedColors.RED.getIndex(), IndexedColors.DARK_GREEN.getIndex(),
                    IndexedColors.BLACK.getIndex(), IndexedColors.BLACK.getIndex()};
            for (int i = 0; i < dayColors.length; i++) {
                var cell = sheet.getRow(2).getCell(4 + i);
                assertEquals(dayColors[i], book.getFontAt(cell.getCellStyle().getFontIndex()).getColor());
            }
            assertEquals("0", sheet.getRow(2).getCell(4).getCellStyle().getDataFormatString());
            assertEquals("[hh]:mm", sheet.getRow(2).getCell(5).getCellStyle().getDataFormatString());
            assertEquals(346 / 1440.0, sheet.getRow(2).getCell(34).getNumericCellValue());
            assertEquals("05:46", formatter.formatCellValue(sheet.getRow(2).getCell(34)));
            assertEquals(6.0, sheet.getRow(2).getCell(35).getNumericCellValue());
            assertEquals(2535 / 1440.0, sheet.getRow(2).getCell(36).getNumericCellValue());
            assertEquals("42:15", formatter.formatCellValue(sheet.getRow(2).getCell(36)));
            assertEquals(IndexedColors.RED.getIndex(), book.getFontAt(sheet.getRow(2).getCell(2).getCellStyle().getFontIndex()).getColor());
            org.assertj.core.api.Assertions.assertThat(sheet.getRow(sheet.getLastRowNum()).getCell(0).getStringCellValue()).contains("işdən azad");
        }
    }

    @Test
    void mcgFutureDaysExportZeroWithoutCountingWorkOrAbsence() throws Exception {
        TenantContext.setTenantId(7L);
        var employee = new Employee(); employee.setId(11L); employee.setTenantId(7L);
        when(employeeRepository.findByTenantId(7L, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(employee)));
        when(mcgProjectionService.forMonth(any(), any(), any(), any())).thenReturn((e, date, summary) ->
                new TabelMonthlyDTO.McgDayDTO(false, 0, 0, 0, 0, 0, "FUTURE", true, 480, date.getDayOfMonth() == 2));
        var month = java.time.YearMonth.from(com.hic.util.AppTimeZone.today()).plusMonths(1);
        var data = tabelService.getMonthlyTabel(month.getYear(), month.getMonthValue(), null, null, null, null,
                TabelMonthlyDTO.CalculationMode.MCG);
        var row = data.getRows().get(0);
        assertEquals(0, row.getWorkingDays()); assertEquals(0, row.getFullDays()); assertEquals(0.0, row.getTotalHours());
        assertNull(row.getDaily().get(1));
        assertEquals("Q/İ", row.getDaily().get(2));
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(tabelService.exportMonthlyTabel(
                month.getYear(), month.getMonthValue(), null, null, null, null, TabelMonthlyDTO.CalculationMode.MCG)))) {
            var sheet = book.getSheet("Tabel");
            for (int day = 1; day <= month.lengthOfMonth(); day++) {
                if (day == 2) assertEquals("Q/İ", sheet.getRow(2).getCell(3 + day).getStringCellValue());
                else assertEquals(0.0, sheet.getRow(2).getCell(3 + day).getNumericCellValue());
            }
        }
    }

    @Test
    void getMonthlyTabel_includesEmployeeAssignedToSelectedAdditionalArea() throws Exception {
        TenantContext.setTenantId(7L);

        Employee employee = new Employee();
        employee.setId(11L);
        employee.setTenantId(7L);
        employee.setBranchId(1L);
        employee.setFirstName("Ayla");
        employee.setLastName("Aliyeva");
        employee.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);

        EmployeeArea membership = new EmployeeArea();
        membership.setTenantId(7L);
        membership.setEmployeeId(11L);
        membership.setBranchId(3L);

        when(employeeAreaRepository.findByBranchId(3L)).thenReturn(List.of(membership));
        when(employeeRepository.findByTenantId(7L, Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(employee)));
        when(positionRepository.findByTenantId(7L)).thenReturn(List.of());
        when(leaveRequestRepository.findApprovedByTenantAndEmployeeIdsAndDateRange(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(employeePermissionRepository.findByDateRange(any(), any(), any())).thenReturn(List.of());
        when(holidayPermissionRepository.findOverlapping(any(), any(), any())).thenReturn(List.of());
        when(dailyAttendanceSummaryRepository.findByEmployeeIdAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        when(attendanceScheduleResolver.resolve(any(), any())).thenReturn(
                new AttendanceScheduleResolver.DaySchedule(1L, "STANDARD", false, null, null, 60, 30, 30));

        TabelMonthlyDTO result = tabelService.getMonthlyTabel(2026, 4, 3L, null, null, null);

        assertEquals(1, result.getEmployees());
        assertEquals(11L, result.getRows().get(0).getEmployeePk());
        assertEquals(Employee.EmploymentStatus.TERMINATED,
                result.getRows().get(0).getEmploymentStatus());

        byte[] excel = tabelService.exportMonthlyTabel(2026, 4, 3L, null, null, null);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(excel))) {
            var sheet = workbook.getSheet("Tabel");
            assertEquals(36, sheet.getRow(1).getLastCellNum(),
                    "Excel-də status və terminationDate üçün əlavə sütun olmamalıdır");
            var nameCell = sheet.getRow(2).getCell(2);
            var font = workbook.getFontAt(nameCell.getCellStyle().getFontIndex());
            assertEquals(IndexedColors.RED.getIndex(), font.getColor());
        }
    }
}
