package com.hic.service;

import com.hic.model.AttendanceLog;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnprAttendanceServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AnprBridgeClient bridge = mock(AnprBridgeClient.class);
    private final EmployeeVehicleService vehicles = mock(EmployeeVehicleService.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AttendanceLogRepository logs = mock(AttendanceLogRepository.class);
    private final AttendanceScheduleResolver schedules = mock(AttendanceScheduleResolver.class);
    private final AnprAttendanceService service = new AnprAttendanceService(jdbc, bridge, vehicles, employees,
            logs, schedules, new AttendanceSessionPolicy(schedules));

    @Test
    void archiveBeforeCameraConfigurationChangeCannotUseCurrentRole() {
        var camera = new DeviceConfig();
        camera.setBranchId(7L);
        camera.setDoorRole("EXIT");
        camera.setAnprArchiveSafeSince(DAY.atTime(12, 0));
        var capture = new AnprBridgeClient.Capture(1L, OffsetDateTime.parse("2026-11-10T08:00:00+04:00"),
                "10AB123", 95, "1", true);
        var changed = new HashSet<AnprAttendanceService.WorkDay>();
        assertThat(service.persistCamera(1, camera, 10, List.of(), List.of(capture), DAY, DAY, changed).skipped())
                .isEqualTo(1);
        assertThat(changed).isEmpty();
        verifyNoInteractions(jdbc, vehicles, employees);
    }

    @Test
    void projectsFirstEntryAndLastExitAcrossCameraAndTerminal() throws Exception {
        employeeWithSchedule("STANDARD", 9, 18);
        sourceRows(
                new Source(1, 101, "2026-11-10T08:45", "FACE", "ENTRY"),
                new Source(2, 102, "2026-11-10T08:50", "ANPR", "ENTRY"),
                new Source(3, 103, "2026-11-10T18:00", "FACE", "EXIT"),
                new Source(4, 104, "2026-11-10T18:15", "ANPR", "EXIT"));
        AttendanceLog result = service.project(1L, new AnprAttendanceService.WorkDay(7, 11, DAY));
        assertThat(result.getCheckInTime()).isEqualTo("2026-11-10T08:45");
        assertThat(result.getCheckOutTime()).isEqualTo("2026-11-10T18:15");
        assertThat(result.getEntryMethod()).isEqualTo("FACE");
        assertThat(result.getExitMethod()).isEqualTo("ANPR");
        assertThat(AttendanceSessionPolicy.calendarDayDeadline(result)).isEqualTo("2026-11-11T00:00");
        assertThat(result.getAnprExitId()).isEqualTo(4);
    }

    @Test
    void closesOvernightCameraSessionOnlyWithCameraExit() throws Exception {
        var employee = employeeWithSchedule("NIGHT", 21, 6);
        assertThat(service.workDate(employee, LocalDateTime.parse("2026-11-11T06:15"), "EXIT")).isEqualTo(DAY);
        sourceRows(
                new Source(1, 102, "2026-11-10T20:55", "ANPR", "ENTRY"),
                new Source(2, 104, "2026-11-11T06:15", "ANPR", "EXIT"),
                new Source(3, 103, "2026-11-11T06:30", "FACE", "EXIT"));
        AttendanceLog result = service.project(1L, new AnprAttendanceService.WorkDay(7, 11, DAY));
        assertThat(result.getCheckOutTime()).isEqualTo("2026-11-11T06:15");
        assertThat(result.getExitMethod()).isEqualTo("ANPR");
    }

    @Test
    void combinedTerminalKeepsDaytimeEntryAndExitWhenMethodsDiffer() throws Exception {
        employeeWithSchedule("STANDARD", 9, 18);
        sourceRows(new Source(1, 101, "2026-11-10T08:45", "FACE", "BOTH"),
                new Source(2, 101, "2026-11-10T18:12", "CARD", "BOTH"));
        var result = service.project(1L, new AnprAttendanceService.WorkDay(7, 11, DAY));
        assertThat(result.getEntryMethod()).isEqualTo("FACE");
        assertThat(result.getExitMethod()).isEqualTo("CARD");
        assertThat(result.getCheckOutTime()).isEqualTo("2026-11-10T18:12");
    }

    @Test
    void overnightCutoffMatchesSessionExpiryAndRejectsBoundaryExit() throws Exception {
        var employee = employeeWithSchedule("NIGHT", 21, 6);
        var cutoff = DAY.plusDays(1).atTime(12, 0);
        assertThat(service.workDate(employee, cutoff.minusSeconds(1), "EXIT")).isEqualTo(DAY);
        assertThat(service.workDate(employee, cutoff, "EXIT")).isEqualTo(DAY.plusDays(1));
        sourceRows(new Source(1, 101, "2026-11-10T20:50", "FACE", "BOTH"),
                new Source(2, 101, "2026-11-11T12:00", "FACE", "BOTH"));
        var result = service.project(1L, new AnprAttendanceService.WorkDay(7, 11, DAY));
        var policy = new AttendanceSessionPolicy(schedules);
        assertThat(result.getAnprDeadline()).isEqualTo(cutoff);
        assertThat(result.getCheckOutTime()).isNull();
        assertThat(policy.isExpired(employee, result, cutoff.minusSeconds(1))).isFalse();
        assertThat(policy.isExpired(employee, result, cutoff)).isTrue();
    }

    @Test
    void directionalEntryAfterMidnightKeepsActualTimeOnPreviousNightShift() throws Exception {
        var employee = employeeWithSchedule("NIGHT", 21, 6);
        var arrival = DAY.plusDays(1).atTime(0, 10);
        assertThat(service.workDate(employee, arrival, "ENTRY")).isEqualTo(DAY);
        assertThat(service.workDate(employee, DAY.plusDays(1).atTime(6, 0), "ENTRY")).isEqualTo(DAY.plusDays(1));
        sourceRows(new Source(1, 102, "2026-11-11T00:10", "ANPR", "ENTRY"),
                new Source(2, 104, "2026-11-11T06:00", "ANPR", "EXIT"));
        var result = service.project(1L, new AnprAttendanceService.WorkDay(7, 11, DAY));
        assertThat(result.getCheckInTime()).isEqualTo(arrival);
        assertThat(AttendanceDayAllocation.affectedDates(result, "NIGHT")).containsExactly(DAY);
        assertThat(AttendanceDayAllocation.forDay(List.of(result), DAY.plusDays(1), day -> "NIGHT")).isEmpty();
        var inference = new AttendanceInferenceService().inferDay(List.of(result), DAY);
        assertThat(inference.firstEntry()).isEqualTo(arrival);
        assertThat(inference.workedMinutes()).isEqualTo(350);
    }

    @Test
    void terminalUsesLegacyRoutingDuringGapBetweenCameraPeriods() {
        var employee = employeeWithSchedule("STANDARD", 9, 18);
        var periods = List.of(new AnprAreaConfiguration.Period(DAY.minusDays(2), DAY),
                new AnprAreaConfiguration.Period(DAY.plusDays(1), null));
        var punch = new com.hic.dto.AttendanceLogSyncDTO.AttendanceLogEntryDTO(1L, 101L, "fixture",
                OffsetDateTime.parse("2026-11-10T09:00:00+04:00"), null);
        assertThat(service.captureTerminal(1, employee, 7L, periods, "ENTRY", punch, new HashSet<>())).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test
    void captureResolvesPlateAtEventTimeAndDoesNotReprojectDuplicate() {
        var employee = employeeWithSchedule("STANDARD", 9, 18);
        var camera = new DeviceConfig();
        camera.setBranchId(7L);
        camera.setDoorRole("ENTRY");
        var time = OffsetDateTime.parse("2026-11-10T08:45:00+04:00");
        when(bridge.captures(102, time.minusHours(1), time.plusHours(1)))
                .thenReturn(List.of(new AnprBridgeClient.Capture(8, time, "10-AB-123", 98, "1")));
        when(vehicles.ownerAt(1, "10AB123", time.toLocalDateTime())).thenReturn(employee.getId());
        when(jdbc.update(anyString(), eq(1L), eq(7L), eq(11L), eq(DAY), eq(102L), isNull(),
                eq(8L), eq("ANPR"), eq("ENTRY"), eq(time.toLocalDateTime()), eq("10-AB-123"), eq(98), isNull()))
                .thenReturn(1, 0);
        var changed = new HashSet<AnprAttendanceService.WorkDay>();
        var periods = List.of(new AnprAreaConfiguration.Period(DAY, null));
        service.captureCamera(1, camera, 102, periods, time.minusHours(1), time.plusHours(1), changed);
        assertThat(changed).containsExactly(new AnprAttendanceService.WorkDay(7, 11, DAY));
        changed.clear();
        service.captureCamera(1, camera, 102, periods, time.minusHours(1), time.plusHours(1), changed);
        assertThat(changed).isEmpty();
    }

    @Test
    void manualCorrectionPreventsReprojection() {
        employeeWithSchedule("STANDARD", 9, 18);
        var corrected = new AttendanceLog();
        corrected.setManualOverride(true);
        when(logs.findByTenantIdAndEmployeeIdAndAnprAreaIdAndAnprWorkDate(1L, 11L, 7L, DAY))
                .thenReturn(Optional.of(corrected));
        assertThat(service.project(1L, new AnprAttendanceService.WorkDay(7, 11, DAY))).isNull();
        verifyNoInteractions(jdbc);
        verify(logs, never()).save(any());
    }

    private Employee employeeWithSchedule(String type, int start, int end) {
        var employee = new Employee();
        employee.setId(11L);
        employee.setTenantId(1L);
        when(employees.findByTenantIdAndId(1L, 11L)).thenReturn(Optional.of(employee));
        when(schedules.resolve(eq(employee), any(LocalDate.class))).thenReturn(
                new AttendanceScheduleResolver.DaySchedule(null, type, true,
                        LocalTime.of(start, 0), LocalTime.of(end, 0), 0, 0, 0));
        when(logs.save(any(AttendanceLog.class))).thenAnswer(call -> call.getArgument(0));
        return employee;
    }

    @SuppressWarnings("unchecked")
    private void sourceRows(Source... sources) throws Exception {
        List<ResultSet> rows = new ArrayList<>();
        for (Source source : sources) {
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(source.id());
            when(row.getLong(2)).thenReturn(source.device());
            when(row.getObject(3, LocalDateTime.class)).thenReturn(LocalDateTime.parse(source.time()));
            when(row.getString(4)).thenReturn(source.method());
            when(row.getString(5)).thenReturn(source.direction());
            rows.add(row);
        }
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(7L), eq(11L), eq(DAY)))
                .thenAnswer(call -> {
                    RowMapper<Object> mapper = call.getArgument(1);
                    List<Object> mapped = new ArrayList<>();
                    for (ResultSet row : rows) mapped.add(mapper.mapRow(row, mapped.size()));
                    return mapped;
                });
    }

    private record Source(long id, long device, String time, String method, String direction) {}
}
