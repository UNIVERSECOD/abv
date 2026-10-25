package com.hic.service;

import com.hic.dto.*;
import com.hic.exception.BadRequestException;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttendanceOverviewServiceTest {
    @Mock EmployeeRepository employees;
    @Mock EmployeeAreaRepository employeeAreas;
    @Mock AttendanceLogRepository logs;
    @Mock DepartmentRepository departments;
    @Mock PositionRepository positions;
    @Mock BranchRepository branches;
    @Mock TimetableRepository timetables;
    @Mock AttendanceService attendance;
    @Mock AttendanceReportService details;
    @Mock AttendanceScheduleResolver scheduleResolver;
    @Spy AttendanceInferenceService inferenceService = new AttendanceInferenceService();
    @Mock UserScopeService userScope;
    @Mock LeaveRequestRepository leaveRequests;
    @InjectMocks AttendanceOverviewService service;
    Employee employee;
    LocalDate start = LocalDate.of(2026, 9, 1);
    AttendanceReportQuery query;

    @BeforeEach void setup() {
        TenantContext.setTenantId(7L);
        lenient().when(userScope.resolveBranchScope(null)).thenReturn(null);
        employee = new Employee(); employee.setId(1L); employee.setTenantId(7L);
        employee.setFirstName("İlham"); employee.setLastName("Test"); employee.setFinNumber("FIN1234");
        employee.setEmployeeId("HR1"); employee.setDepartmentId(10L); employee.setPositionId(20L); employee.setBranchId(30L);
        lenient().when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(7L)).thenReturn(List.of(employee));
        Timetable table = new Timetable(); table.setId(40L); table.setName("Səhər");
        lenient().when(timetables.findByTenantId(7L)).thenReturn(List.of(table));
        BiFunction<Employee, LocalDate, AttendanceScheduleResolver.DaySchedule> schedules = (e, d) ->
                new AttendanceScheduleResolver.DaySchedule(40L, "STANDARD", true, LocalTime.of(9, 0), LocalTime.of(18, 0), 0, 5, 0);
        lenient().when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn(schedules);
        lenient().when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start, 8, 25, 30, "EARLY_LEAVE"),
                        day(start.plusDays(1), 0, 10, 0, "MISSING_EXIT"), day(start.plusDays(2), 0, 0, 0, "ABSENT")));
        query = new AttendanceReportQuery(); query.setView(AttendanceReportQuery.View.DAILY);
        query.setStart(start); query.setEnd(start.plusDays(2));
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void leaveAttendanceWarningRequiresBothApprovedLeaveAndAttendance() {
        var leave = new LeaveRequest(); leave.setEmployeeId(1L); leave.setStartDate(start);
        leave.setEndDate(start.plusDays(2)); leave.setStatus(LeaveRequest.LeaveStatus.APPROVED);
        when(leaveRequests.findApprovedByTenantAndEmployeeIdsAndDateRange(7L, List.of(1L), start, start.plusDays(2)))
                .thenReturn(List.of(leave));
        query.setStatus("WORKING_ON_LEAVE");
        var rows = service.report(query).getContent();
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatuses()).contains("ON_LEAVE", "WORKING_ON_LEAVE");
            assertThat(row.getCheckInTime()).isNotNull();
        });
        assertThat(rows).noneMatch(row -> row.getDate().equals(start.plusDays(2)));
    }

    @Test void missingExitFilterAndExcelIncludeOpenSessions() throws Exception {
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start, 0, 10, 0, "OPEN_SESSION"),
                        day(start.plusDays(1), 0, 0, 0, "MISSING_EXIT"), day(start.plusDays(2), 8, 0, 0, "PRESENT")));
        query.setStatus("MISSING_EXIT");
        assertThat(service.report(query).getTotalElements()).isEqualTo(2);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).toString()).doesNotContain("Açıq sessiya");
        }
        assertThat(AttendanceOverviewService.statusLabel("OPEN")).isEqualTo("Çıxış yoxdur");
        assertThat(AttendanceOverviewService.statusLabel("OPEN_SESSION")).isEqualTo("İşdə");
    }

    @Test void livePresenceFilterExcludesPastOpenAndCompletedDaysWithoutChangingMinutes() throws Exception {
        var today = com.hic.util.AppTimeZone.today();
        query.setStart(today.minusDays(2)); query.setEnd(today);
        var pastOpen = day(today.minusDays(2), 0, 0, 0, "OPEN_SESSION");
        var completed = day(today.minusDays(1), 9.5, 0, 0, "PRESENT");
        completed.setCheckOutTime(today.minusDays(1).atTime(19, 0).atOffset(ZoneOffset.ofHours(4)));
        recordedVisits(source(today.minusDays(1), "09:30", "19:00"));
        // An open-only day remains live; mixed closed/open days have a separate regression below.
        var inside = day(today, 4, 5, 0, "OPEN_SESSION");
        inside.setCheckOutTime(null);
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(pastOpen, completed, inside));
        var rows = service.report(query).getContent();
        assertThat(rows).extracting(AttendanceOverviewRowDTO::getStatus)
                .containsExactly("MISSING_EXIT", "PRESENT", "OPEN_SESSION");
        assertThat(rows).extracting(AttendanceOverviewRowDTO::getWorkedMinutes).containsExactly(0, 570, 240);
        assertThat(rows).extracting(AttendanceOverviewRowDTO::getOvertimeMinutes).containsExactly(0, 60, 0);
        assertThat(rows).extracting(AttendanceOverviewRowDTO::getMissingExitDays).containsExactly(1, 0, 1);
        query.setStatus("OPEN_SESSION");
        assertThat(service.report(query).getContent()).singleElement().satisfies(row -> {
            assertThat(row.getDate()).isEqualTo(today);
            assertThat(row.getStatuses()).contains("OPEN_SESSION", "LATE").doesNotContain("PRESENT", "WORKDAY_COMPLETE");
            assertThat(row.getCheckOutTime()).isEqualTo(inside.getCheckOutTime());
        });
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(12).getStringCellValue())
                    .isEqualTo("İşdə, Gecikib");
        }
        query.setStatus("WORKDAY_COMPLETE");
        assertThat(service.report(query).getContent()).singleElement()
                .satisfies(row -> assertThat(row.getDate()).isEqualTo(today.minusDays(1)));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(12).getStringCellValue())
                    .isEqualTo("İşdə olub, Overtime");
        }
        verify(logs, never()).save(any());
        verify(attendance, never()).generateDailySummary(any(), any());
    }

    @Test void livePresenceBecomesMissingExitAtBakuCalendarBoundaryOnlyInReport() {
        var date = LocalDate.of(2026, 10, 12);
        query.setStart(date); query.setEnd(date.plusDays(1));
        var open = day(date, 0, 0, 0, "OPEN_SESSION");
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any())).thenReturn(List.of(open));
        try (var clock = mockStatic(com.hic.util.AppTimeZone.class)) {
            clock.when(com.hic.util.AppTimeZone::today).thenReturn(date);
            assertThat(service.report(query).getContent().get(0).getStatus()).isEqualTo("OPEN_SESSION");
            clock.when(com.hic.util.AppTimeZone::today).thenReturn(date.plusDays(1));
            assertThat(service.report(query).getContent().get(0).getStatus()).isEqualTo("MISSING_EXIT");
        }
        assertThat(open.getStatus()).isEqualTo(DailyAttendanceSummary.AttendanceStatus.OPEN_SESSION);
    }

    @Test void sameDayUnclosedVisitStaysLiveEvenAfterThePairingWindowExpires() {
        var today = com.hic.util.AppTimeZone.today();
        query.setStart(today); query.setEnd(today); query.setStatus("OPEN_SESSION");
        var open = day(today, 0, 0, 0, "MISSING_EXIT");
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any())).thenReturn(List.of(open));
        assertThat(service.report(query).getContent()).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo("OPEN_SESSION"));
        assertThat(open.getStatus()).isEqualTo(DailyAttendanceSummary.AttendanceStatus.MISSING_EXIT);
        open.setCheckInTime(null);
        assertThat(service.report(query).getContent()).isEmpty();
    }

    @Test void detailStatusAndExcelDistinguishClosedTodayOpenAndPastOpenVisits() throws Exception {
        var today = com.hic.util.AppTimeZone.today();
        start = today.minusDays(1);
        query.setStart(start); query.setEnd(today); query.setView(AttendanceReportQuery.View.DETAILS);
        var closed = visit(1L, 9, 12); closed.setStatus("CLOSED");
        var pastOpen = visit(2L, 13, null); pastOpen.setStatus("OPEN");
        var current = visit(3L, 14, null); current.setDate(today); current.setStatus("OPEN");
        current.setCheckInTime(today.atTime(14, 0).atOffset(ZoneOffset.ofHours(4)));
        when(details.getReport(start, today, null, null, null, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(PaginatedResponse.of(List.of(closed, pastOpen, current), 3, 1, 0, Integer.MAX_VALUE));
        assertThat(service.report(query).getContent()).extracting(AttendanceOverviewRowDTO::getStatus)
                .containsExactlyInAnyOrder("CLOSED", "MISSING_EXIT", "OPEN_SESSION");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(List.of(sheet.getRow(1).getCell(11).getStringCellValue(),
                    sheet.getRow(2).getCell(11).getStringCellValue(), sheet.getRow(3).getCell(11).getStringCellValue()))
                    .containsExactlyInAnyOrder("İşdə olub", "Çıxış yoxdur", "İşdə");
        }
        assertThat(AttendanceOverviewService.statusLabel("WORKDAY_COMPLETE")).isEqualTo("İşdə olub");
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(pastOpen.getStatus()).isEqualTo("OPEN");
    }

    @Test void lateAndEarlyRemainIndependentlyVisibleAndFilterable() {
        query.setStatus("LATE");
        var late = service.report(query);
        assertThat(late.getTotalElements()).isEqualTo(2);
        assertThat(late.getContent().get(0).getStatuses()).contains("LATE", "EARLY_LEAVE");
        query.setStatus("EARLY_LEAVE");
        assertThat(service.report(query).getTotalElements()).isEqualTo(1);
        query.setStatus("LATE_AND_EARLY");
        assertThat(service.report(query).getTotalElements()).isEqualTo(1);
        query.setStatus("MISSING_EXIT");
        assertThat(service.report(query).getContent().get(0).getEarlyLeaveMinutes()).isZero();
    }

    @Test void monthlyAndYearlySumDaysOnceAndPaginationFollowsFiltering() {
        for (var view : List.of(AttendanceReportQuery.View.MONTHLY, AttendanceReportQuery.View.YEARLY)) {
            query.setView(view);
            var result = service.report(query);
            assertThat(result.getTotalElements()).isEqualTo(1);
            var row = result.getContent().get(0);
            assertThat(row.getWorkedMinutes()).isEqualTo(480);
            assertThat(row.getLateMinutes()).isEqualTo(35);
            assertThat(row.getEarlyLeaveMinutes()).isEqualTo(30);
            assertThat(row.getLateDays()).isEqualTo(2);
            assertThat(row.getAbsentDays()).isEqualTo(1);
            assertThat(row.getMissingExitDays()).isEqualTo(1);
            assertThat(row.getCheckInTime()).isNull();
        }
        query.setView(AttendanceReportQuery.View.DAILY); query.setStatus("LATE"); query.setSize(1); query.setPage(1);
        var page = service.report(query);
        assertThat(page.getTotalElements()).isEqualTo(2); assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent().get(0).getDate()).isEqualTo(start.plusDays(1));
    }

    @Test void exactIdFiltersAndAzerbaijaniNameSearchApplyBeforeCalculation() {
        query.setName("ilham"); query.setFin("123"); query.setDepartmentId(10L); query.setPositionId(20L); query.setTimetableId(40L);
        assertThat(service.report(query).getTotalElements()).isEqualTo(3);
        query.setTimetableId(41L); assertThat(service.report(query).getTotalElements()).isZero();
        query.setTimetableId(40L); query.setDepartmentId(11L);
        assertThat(service.report(query).getTotalElements()).isZero();
    }

    @Test void frozenVisitAreaWinsOverCurrentEmployeeMembership() {
        AttendanceLog log = new AttendanceLog(); log.setId(8L); log.setEmployeeId(1L);
        log.setCheckInTime(start.atTime(9, 0)); log.setAreaSnapshotCaptured(true); log.setAreaSnapshotId(99L); log.setAreaSnapshotName("Tarixi ərazi");
        when(logs.findByTenantIdAndCheckInTimeBetween(eq(7L), any(), any())).thenReturn(List.of(log));
        query.setAreaId(99L);
        assertThat(service.report(query).getContent()).singleElement().satisfies(row -> assertThat(row.getArea()).isEqualTo("Tarixi ərazi"));
        query.setAreaId(30L);
        assertThat(service.report(query).getContent()).extracting(AttendanceOverviewRowDTO::getDate).doesNotContain(start);
    }

    @Test void tenantAndBranchScopeNeverExposeOtherEmployees() {
        Employee foreign = new Employee(); foreign.setId(2L); foreign.setTenantId(8L);
        when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(7L)).thenReturn(List.of(employee, foreign));
        assertThat(service.report(query).getContent()).allMatch(row -> row.getEmployeePk().equals(1L));
        when(userScope.resolveBranchScope(null)).thenReturn(99L);
        assertThat(service.report(query).getTotalElements()).isZero();
        verify(attendance, never()).getEmployeeAttendanceForReport(eq(foreign), any(), any(), any());
    }

    @Test void exportUsesIdenticalStatusFiltersAndExportsAllPages() throws Exception {
        query.setStatus("LATE"); query.setSize(1); query.setPage(1);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(12).getStringCellValue()).contains("Gecikib", "Erkən çıxıb");
            assertThat(sheet.getRow(1).getCell(13).getStringCellValue()).isEqualTo("0 saat 25 dəqiqə");
        }
        verify(logs, never()).save(any());
        verify(attendance, never()).generateDailySummary(any(), any());
    }

    @Test void invalidRangesAndNonDailyStatusAreRejectedForBothFormats() {
        query.setEnd(start.minusDays(1));
        assertThatThrownBy(() -> service.report(query)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.export(query)).isInstanceOf(BadRequestException.class);
        query.setEnd(start.plusDays(366));
        assertThatThrownBy(() -> service.report(query)).isInstanceOf(BadRequestException.class);
        query.setEnd(start); query.setView(AttendanceReportQuery.View.YEARLY); query.setStatus("LATE");
        assertThatThrownBy(() -> service.export(query)).isInstanceOf(BadRequestException.class);
    }

    @Test void historicalTerminatedEmployeeKeepsVisitsWithoutInventedAbsences() {
        employee.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);
        assertThat(service.report(query).getContent()).hasSize(2);
    }

    @Test void hireDateAndTodayBoundTheCalculationWindow() {
        employee.setHireDate(start.plusDays(1)); query.setEnd(start.plusDays(3));
        service.report(query);
        verify(attendance).getEmployeeAttendanceForReport(eq(employee), eq(start.plusDays(1)), eq(start.plusDays(3)), any());
        query.setStart(com.hic.util.AppTimeZone.today().plusDays(1)); query.setEnd(query.getStart().plusDays(1));
        assertThat(service.report(query).getTotalElements()).isZero();
    }

    @Test void detailSearchUsesSameAzerbaijaniNameFilterAsDaily() {
        query.setView(AttendanceReportQuery.View.DETAILS); query.setName("ilham");
        var visit = new AttendanceReportRowDTO(); visit.setEmployeePk(1L); visit.setDate(start);
        when(details.getReport(start, query.getEnd(), null, null, null, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(PaginatedResponse.of(List.of(visit), 1, 1, 0, Integer.MAX_VALUE));
        assertThat(service.report(query).getTotalElements()).isEqualTo(1);
        query.setName("another");
        assertThat(service.report(query).getTotalElements()).isZero();
    }

    @Test void overtimeUsesScheduledEndAndDoesNotCancelAgainstShortDays() throws Exception {
        recordedVisits(source(start, "09:00", "19:30"), source(start.plusDays(1), "09:00", "13:00"),
                source(start.plusDays(2), "09:00", "19:00"));
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start, 10.5, 0, 0, "PRESENT"),
                        day(start.plusDays(1), 4, 0, 0, "EARLY_LEAVE"), day(start.plusDays(2), 10, 0, 0, "PRESENT")));
        assertThat(service.report(query).getContent()).extracting(AttendanceOverviewRowDTO::getOvertimeMinutes)
                .containsExactly(90, 0, 60);
        for (var view : List.of(AttendanceReportQuery.View.MONTHLY, AttendanceReportQuery.View.YEARLY)) {
            query.setView(view);
            var row = service.report(query).getContent().get(0);
            assertThat(row.getWorkedMinutes()).isEqualTo(1470);
            assertThat(row.getOvertimeMinutes()).isEqualTo(150);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getRow(0).getCell(9).getStringCellValue()).isEqualTo("Overtime");
                assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("24 saat 30 dəqiqə");
                assertThat(sheet.getRow(1).getCell(9).getStringCellValue()).isEqualTo("2 saat 30 dəqiqə");
            }
        }
    }

    @Test void flexibleDaysHaveNoOvertimeNormAndMixedPeriodsKeepStandardOvertime() {
        recordedVisits(source(start, "09:00", "19:00"));
        when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn((e, date) ->
                new AttendanceScheduleResolver.DaySchedule(40L, date.equals(start) ? "STANDARD" : "FLEXIBLE", true,
                        LocalTime.of(9, 0), LocalTime.of(18, 0), 60, 0, 0));
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start, 9, 0, 0, "PRESENT"), day(start.plusDays(1), 12, 0, 0, "PRESENT")));
        assertThat(service.report(query).getContent()).extracting(AttendanceOverviewRowDTO::getOvertimeMinutes)
                .containsExactly(60, null);
        query.setView(AttendanceReportQuery.View.MONTHLY);
        assertThat(service.report(query).getContent().get(0).getOvertimeMinutes()).isEqualTo(60);
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start.plusDays(1), 12, 0, 0, "PRESENT")));
        assertThat(service.report(query).getContent().get(0).getOvertimeMinutes()).isNull();
    }

    @Test void nightAndRestDayOvertimeExcludeCreditedPermissionAndMissingExit() {
        recordedVisits(source(start, "20:00", "03:30"), source(start.plusDays(1), "09:00", "13:00"));
        when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn((e, date) ->
                new AttendanceScheduleResolver.DaySchedule(40L, "STANDARD", date.equals(start),
                        LocalTime.of(20, 0), LocalTime.of(2, 0), 0, 0, 0));
        var night = day(start, 7.5, 0, 0, "PRESENT");
        night.setCheckInTime(start.atTime(20, 0).atOffset(ZoneOffset.ofHours(4)));
        night.setCheckOutTime(start.plusDays(1).atTime(3, 30).atOffset(ZoneOffset.ofHours(4)));
        var rest = day(start.plusDays(1), 4, 0, 0, "ON_PERMISSION");
        rest.setCreditedPermissionMinutes(60);
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(night, rest, day(start.plusDays(2), 0, 0, 0, "MISSING_EXIT")));
        assertThat(service.report(query).getContent()).extracting(AttendanceOverviewRowDTO::getOvertimeMinutes)
                .containsExactly(90, 0, 0);
    }

    @Test void detailsAndExportOmitOvertimeWhilePreservingVisitsAndAreaFiltering() throws Exception {
        query.setView(AttendanceReportQuery.View.DETAILS);
        var first = visit(1L, 9, 12); var last = visit(2L, 13, 20); var open = visit(3L, 21, null);
        when(details.getReport(start, query.getEnd(), null, null, null, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(PaginatedResponse.of(List.of(open, last, first), 3, 1, 0, Integer.MAX_VALUE));
        query.setSize(1); query.setPage(1);
        assertThat(service.report(query).getContent()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getAttendanceLogId()).isEqualTo(2L);
                    assertThat(row.getOvertimeMinutes()).isNull();
                    assertThat(row.getStatuses()).doesNotContain("OVERTIME");
                });
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            var sheet = workbook.getSheetAt(0);
            for (var cell : sheet.getRow(0)) assertThat(cell.getStringCellValue()).isNotEqualTo("Overtime");
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 12);
            assertThat(sheet.getRow(0).getCell(11).getStringCellValue()).isEqualTo("Status");
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            for (int i = 1; i <= 3; i++) {
                assertThat(sheet.getRow(i).getLastCellNum()).isEqualTo((short) 12);
                assertThat(sheet.getRow(i).getCell(11).getStringCellValue()).doesNotContain("Overtime");
            }
        }
        AttendanceLog firstLog = new AttendanceLog(); firstLog.setId(1L); firstLog.setEmployeeId(1L);
        firstLog.setCheckInTime(start.atTime(9, 0)); firstLog.setAreaSnapshotCaptured(true); firstLog.setAreaSnapshotId(30L);
        AttendanceLog lastLog = new AttendanceLog(); lastLog.setId(2L); lastLog.setEmployeeId(1L);
        lastLog.setCheckInTime(start.atTime(13, 0)); lastLog.setAreaSnapshotCaptured(true); lastLog.setAreaSnapshotId(99L);
        when(logs.findByTenantIdAndCheckInTimeBetween(eq(7L), any(), any())).thenReturn(List.of(firstLog, lastLog));
        query.setAreaId(30L); query.setSize(200); query.setPage(0);
        assertThat(service.report(query).getContent()).allSatisfy(row -> assertThat(row.getOvertimeMinutes()).isNull());
        verify(logs, never()).save(any());
        verify(attendance, never()).generateDailySummary(any(), any());
        verify(attendance, never()).getEmployeeAttendanceForReport(any(), any(), any(), any());
    }

    @Test void sortingPrecedesPaginationAndExcelKeepsTheCompleteSortedResult() throws Exception {
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start, 4, 0, 0, "PRESENT"), day(start.plusDays(1), 12, 0, 0, "PRESENT"),
                        day(start.plusDays(2), 9.5, 0, 0, "PRESENT")));
        query.setSortBy("workedMinutes"); query.setSortDirection("desc"); query.setSize(1);
        assertThat(service.report(query).getContent().get(0).getWorkedMinutes()).isEqualTo(720);
        query.setPage(1);
        assertThat(service.report(query).getContent().get(0).getWorkedMinutes()).isEqualTo(570);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEqualTo("12 saat 0 dəqiqə");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("9 saat 30 dəqiqə");
            assertThat(sheet.getRow(3).getCell(10).getStringCellValue()).isEqualTo("4 saat 0 dəqiqə");
        }
        query.setPage(0); query.setSortDirection("asc");
        assertThat(service.report(query).getContent().get(0).getWorkedMinutes()).isEqualTo(240);
    }

    @Test void missingExitSortFollowsDirectionOnFirstPageAndExcelInBothViews() throws Exception {
        var closed = visit(1L, 9, 18); var open = visit(2L, 19, null);
        lenient().when(details.getReport(start, query.getEnd(), null, null, null, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(PaginatedResponse.of(List.of(closed, open), 2, 1, 0, Integer.MAX_VALUE));
        query.setSortBy("checkOutTime"); query.setSize(1);
        for (var view : List.of(AttendanceReportQuery.View.DETAILS, AttendanceReportQuery.View.DAILY)) {
            query.setView(view);
            for (String direction : List.of("asc", "desc")) {
                query.setSortDirection(direction);
                boolean missingFirst = direction.equals("desc");
                assertThat(service.report(query).getContent()).singleElement()
                        .satisfies(row -> assertThat(row.getCheckOutTime() == null).isEqualTo(missingFirst));
                try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
                    var sheet = workbook.getSheetAt(0);
                    assertThat(sheet.getRow(1).getCell(9).getStringCellValue().isEmpty()).isEqualTo(missingFirst);
                    assertThat(sheet.getRow(sheet.getLastRowNum()).getCell(9).getStringCellValue().isEmpty()).isEqualTo(!missingFirst);
                }
            }
        }
    }

    @Test void multipleStatusesUseUnionBeforePaginationAndExportWithoutDuplicates() throws Exception {
        query.setStatus("LATE,EARLY_LEAVE"); query.setSize(1);
        assertThat(service.report(query).getTotalElements()).isEqualTo(2);
        query.setPage(1);
        assertThat(service.report(query).getContent().get(0).getDate()).isEqualTo(start.plusDays(1));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(2);
        }
        query.setStatus(" LATE, LATE,ABSENT ");
        assertThat(service.report(query).getTotalElements()).isEqualTo(3);
        query.setStatus(null);
        assertThat(service.report(query).getTotalElements()).isEqualTo(3);
    }

    @Test void overtimeFilterAndBadgeRequirePositiveKnownOvertime() {
        recordedVisits(source(start, "09:00", "19:00"));
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(day(start, 10, 0, 0, "PRESENT"), day(start.plusDays(1), 8, 0, 0, "PRESENT")));
        query.setStatus("OVERTIME");
        assertThat(service.report(query).getContent()).singleElement().satisfies(row -> {
            assertThat(row.getOvertimeMinutes()).isEqualTo(60);
            assertThat(row.getStatuses()).contains("OVERTIME");
        });
        when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn((e, d) ->
                new AttendanceScheduleResolver.DaySchedule(40L, "FLEXIBLE", true, null, null, 0, 0, 0));
        assertThat(service.report(query).getTotalElements()).isZero();
    }

    @Test void permissionFilterIncludesMinutesAndLegacyPermittedExitAndExportDeduplicatesLabels() throws Exception {
        var permitted = day(start, 8, 0, 0, "PERMITTED_EARLY_LEAVE"); permitted.setPermissionMinutes(60);
        var partial = day(start.plusDays(1), 8, 0, 0, "PRESENT"); partial.setPermissionMinutes(30);
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(permitted, partial, day(start.plusDays(2), 8, 0, 0, "PRESENT")));
        query.setStatus("ON_PERMISSION");
        assertThat(service.report(query).getTotalElements()).isEqualTo(2);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(12).getStringCellValue()).isEqualTo("İşdə olub, İcazə");
        }
    }

    @Test void completedPresenceBadgeCoexistsWithExceptionsAndFiltersAndExcel() throws Exception {
        var permitted = day(start, 10, 0, 0, "ON_PERMISSION"); permitted.setPermissionMinutes(30);
        var late = day(start.plusDays(1), 8, 15, 20, "EARLY_LEAVE");
        var permissionOnly = day(start.plusDays(2), 0, 0, 0, "ON_PERMISSION");
        permissionOnly.setCheckInTime(null); permissionOnly.setPermissionMinutes(540);
        recordedVisits(source(start, "09:00", "19:00"));
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any()))
                .thenReturn(List.of(permitted, late, permissionOnly));
        var rows = service.report(query).getContent();
        assertThat(rows.get(0).getStatuses()).containsExactly("PRESENT", "ON_PERMISSION", "OVERTIME");
        assertThat(rows.get(1).getStatuses()).contains("PRESENT", "LATE", "EARLY_LEAVE");
        assertThat(rows.get(2).getStatuses()).containsExactly("ON_PERMISSION");
        assertThat(rows).extracting(AttendanceOverviewRowDTO::getWorkedMinutes).containsExactly(600, 480, 0);
        query.setStatus("WORKDAY_COMPLETE");
        assertThat(service.report(query).getContent()).extracting(AttendanceOverviewRowDTO::getDate)
                .containsExactly(start, start.plusDays(1));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(12).getStringCellValue())
                    .isEqualTo("İşdə olub, İcazə, Overtime");
        }
        assertThat(permitted.getStatus()).isEqualTo(DailyAttendanceSummary.AttendanceStatus.ON_PERMISSION);
    }

    @Test void everySelectedStatusIsValidatedAndAggregateViewsStillRejectStatusFilters() {
        for (String value : List.of("LATE,INVALID", "LATE,", ",OVERTIME")) {
            query.setStatus(value);
            assertThatThrownBy(() -> service.report(query)).isInstanceOf(BadRequestException.class);
        }
        query.setStatus("LATE,OVERTIME"); query.setView(AttendanceReportQuery.View.MONTHLY);
        assertThatThrownBy(() -> service.report(query)).isInstanceOf(BadRequestException.class);
    }

    @Test void dailyMonthlyYearlyAndExcelUseTheSameClosedVisitOvertimeAsMcgTabel() throws Exception {
        var schedule = new AttendanceScheduleResolver.DaySchedule(40L, "STANDARD", true,
                LocalTime.of(9, 0), LocalTime.of(16, 0), 0, 5, 5);
        when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn((e, d) -> schedule);
        var visits = List.of(source(start, "13:21", "13:21:30"), source(start, "13:44:30", "17:41"),
                source(start, "20:04:30", "21:17"), source(start, "21:41", "21:46"));
        recordedVisits(visits.toArray(AttendanceLog[]::new));
        var permission = new EmployeePermission(); permission.setStartDate(start); permission.setEndDate(start);
        permission.setStartTime(LocalTime.of(9, 0)); permission.setEndTime(LocalTime.of(13, 30));
        permission.setStatus(EmployeePermission.Status.APPROVED); permission.setDeductFromWorkHours(true);
        var mcg = new McgTabelCalculator(inferenceService, new AttendanceTimeCalculator(), new AttendancePermissionCalculator())
                .calculate(start, visits, schedule, List.of(permission), ignored -> schedule);
        var daily = day(start, 496 / 60.0, 0, 0, "ON_PERMISSION"); daily.setPermissionMinutes(270);
        daily.setCheckInTime(start.atTime(13, 21).atOffset(ZoneOffset.ofHours(4)));
        daily.setCheckOutTime(start.atTime(21, 46).atOffset(ZoneOffset.ofHours(4)));
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any())).thenReturn(List.of(daily));
        assertThat(mcg.overtimeMinutes()).isEqualTo(178);
        assertThat(mcg.workedMinutes()).isEqualTo(496);
        for (var view : List.of(AttendanceReportQuery.View.DAILY, AttendanceReportQuery.View.MONTHLY, AttendanceReportQuery.View.YEARLY)) {
            query.setView(view);
            var row = service.report(query).getContent().get(0);
            assertThat(row.getOvertimeMinutes()).isEqualTo(mcg.overtimeMinutes());
            assertThat(row.getWorkedMinutes()).isEqualTo(496);
            assertThat(row.getPermissionMinutes()).isEqualTo(270);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
                var sheet = workbook.getSheetAt(0);
                int overtimeColumn = view == AttendanceReportQuery.View.DAILY ? 11 : 9;
                assertThat(sheet.getRow(1).getCell(overtimeColumn).getStringCellValue()).isEqualTo("2 saat 58 dəqiqə");
            }
        }
        verify(logs, never()).save(any());
        verify(attendance, never()).generateDailySummary(any(), any());
    }

    @Test void sourceMissingOrUnassignedDaysCannotInventOvertimeFromSummaryHours() {
        var daily = day(start, 12, 0, 0, "PRESENT");
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any())).thenReturn(List.of(daily));
        assertThat(service.report(query).getContent().get(0).getOvertimeMinutes()).isZero();
        recordedVisits(source(start, "09:00", "21:00"));
        when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn((e, d) ->
                new AttendanceScheduleResolver.DaySchedule(null, "STANDARD", true,
                        LocalTime.of(9, 0), LocalTime.of(17, 0), 0, 5, 0, false));
        var row = service.report(query).getContent().get(0);
        assertThat(row.getOvertimeMinutes()).isZero();
        assertThat(row.getWorkedMinutes()).isEqualTo(720);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "1,false", "0,true", "1,true"})
    void completedVisitsAndOpenReentryKeepHoursAndBothBadges(int daysAgo, boolean onLeave) throws Exception {
        var date = com.hic.util.AppTimeZone.today().minusDays(daysAgo);
        query.setStart(date); query.setEnd(date);
        var visits = List.of(source(date, "09:00", "12:00"), source(date, "14:00", "16:00"),
                source(date, "17:00", null));
        recordedVisits(visits.toArray(AttendanceLog[]::new));
        when(logs.findByTenantIdAndEmployeeIdAndCheckInTimeBetween(eq(7L), eq(1L), any(), any())).thenReturn(visits);
        var leaves = mock(LeaveRequestRepository.class);
        if (onLeave) {
            var leave = new LeaveRequest(); leave.setStartDate(date); leave.setEndDate(date);
            leave.setStatus(LeaveRequest.LeaveStatus.APPROVED);
            when(leaves.findApprovedByTenantAndEmployeeIdAndDateRange(eq(7L), eq(1L), any(), any()))
                    .thenReturn(List.of(leave));
        }
        var realAttendance = new AttendanceService(logs, mock(DailyAttendanceSummaryRepository.class), employees,
                leaves, mock(EmployeePermissionRepository.class), userScope, inferenceService, scheduleResolver,
                new AttendanceTimeCalculator(), new AttendancePermissionCalculator());
        var schedules = scheduleResolver.forReport(7L, List.of(employee), date, date);
        var daily = realAttendance.getEmployeeAttendanceForReport(employee, date, date, schedules);
        assertThat(daily.get(0).getHoursWorked()).isEqualTo(5.0); // 3h + 2h, no gap or open-visit time.
        var mcg = new McgTabelCalculator(inferenceService, new AttendanceTimeCalculator(), new AttendancePermissionCalculator())
                .calculate(date, visits, schedules.apply(employee, date), List.of(), d -> schedules.apply(employee, d));
        assertThat(mcg.workedMinutes()).isEqualTo(300);
        assertThat(mcg.fullDay()).isFalse();
        when(attendance.getEmployeeAttendanceForReport(eq(employee), any(), any(), any())).thenReturn(daily);
        var row = service.report(query).getContent().get(0);
        assertThat(row.getWorkedMinutes()).isEqualTo(300);
        assertThat(row.getStatuses()).contains("PRESENT", "MISSING_EXIT").doesNotContain("OPEN_SESSION");
        assertThat(row.getMissingExitDays()).isEqualTo(1);
        if (onLeave) assertThat(row.getStatuses()).contains("ON_LEAVE");
        for (var filter : List.of("WORKDAY_COMPLETE", "MISSING_EXIT")) {
            query.setStatus(filter);
            assertThat(service.report(query).getTotalElements()).isEqualTo(1);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(12).getStringCellValue())
                        .contains("İşdə olub", "Çıxış yoxdur");
            }
        }
        verify(logs, never()).save(any());
        verify(attendance, never()).generateDailySummary(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    void detailsKeepEachVisitOwnMinutesAndStatusInsteadOfDailyBadges(int daysAgo) throws Exception {
        start = com.hic.util.AppTimeZone.today().minusDays(daysAgo);
        query.setStart(start); query.setEnd(start); query.setView(AttendanceReportQuery.View.DETAILS);
        var first = visit(1L, 9, 12); first.setStatus("CLOSED"); first.setWorkedMinutes(180);
        var second = visit(2L, 14, 16); second.setStatus("CLOSED"); second.setWorkedMinutes(120);
        var open = visit(3L, 17, null); open.setStatus("OPEN"); open.setWorkedMinutes(0);
        when(details.getReport(start, start, null, null, null, null, null, null, null, 0, Integer.MAX_VALUE))
                .thenReturn(PaginatedResponse.of(List.of(first, second, open), 3, 1, 0, Integer.MAX_VALUE));
        var rows = service.report(query).getContent();
        assertThat(rows).extracting(AttendanceOverviewRowDTO::getWorkedMinutes).containsExactly(180, 120, 0);
        assertThat(rows.get(0).getStatuses()).containsExactly("CLOSED");
        assertThat(rows.get(1).getStatuses()).containsExactly("CLOSED");
        assertThat(rows.get(2).getStatuses()).containsExactly(daysAgo == 0 ? "OPEN_SESSION" : "MISSING_EXIT");
        assertThat(rows.get(2).getCheckOutTime()).isNull();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.export(query)))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEqualTo("3 saat 0 dəqiqə");
            assertThat(sheet.getRow(2).getCell(10).getStringCellValue()).isEqualTo("2 saat 0 dəqiqə");
            assertThat(sheet.getRow(3).getCell(10).getStringCellValue()).isEqualTo("0 saat 0 dəqiqə");
            assertThat(sheet.getRow(1).getCell(11).getStringCellValue()).isEqualTo("İşdə olub");
            assertThat(sheet.getRow(3).getCell(11).getStringCellValue())
                    .isEqualTo(daysAgo == 0 ? "İşdə" : "Çıxış yoxdur");
        }
        verify(attendance, never()).getEmployeeAttendanceForReport(any(), any(), any(), any());
    }

    private void recordedVisits(AttendanceLog... visits) {
        when(logs.findByTenantIdAndCheckInTimeBetween(eq(7L), any(), any())).thenReturn(List.of(visits));
    }

    private AttendanceLog source(LocalDate date, String entry, String exit) {
        var log = new AttendanceLog(); log.setEmployeeId(1L); log.setTenantId(7L);
        log.setCheckInTime(date.atTime(LocalTime.parse(entry)));
        log.setId(date.toEpochDay() * 86400 + LocalTime.parse(entry).toSecondOfDay());
        if (exit != null) {
            var end = date.atTime(LocalTime.parse(exit));
            log.setCheckOutTime(end.isAfter(log.getCheckInTime()) ? end : end.plusDays(1));
        }
        return log;
    }

    private AttendanceReportRowDTO visit(Long id, int entry, Integer exit) {
        var row = new AttendanceReportRowDTO(); row.setAttendanceLogId(id); row.setEmployeePk(1L); row.setDate(start);
        row.setCheckInTime(start.atTime(entry, 0).atOffset(ZoneOffset.ofHours(4)));
        if (exit != null) row.setCheckOutTime(start.atTime(exit, 0).atOffset(ZoneOffset.ofHours(4)));
        return row;
    }

    private EmployeeAttendanceRowDTO day(LocalDate date, double hours, int late, int early, String status) {
        var row = new EmployeeAttendanceRowDTO(); row.setDate(date); row.setHoursWorked(hours);
        row.setLateMinutes(late); row.setEarlyLeaveMinutes(early);
        row.setStatus(DailyAttendanceSummary.AttendanceStatus.valueOf(status));
        if (!status.equals("ABSENT")) row.setCheckInTime(date.atTime(9, 30).atOffset(ZoneOffset.ofHours(4)));
        if (hours > 0) row.setCheckOutTime(date.atTime(17, 30).atOffset(ZoneOffset.ofHours(4)));
        return row;
    }
}
