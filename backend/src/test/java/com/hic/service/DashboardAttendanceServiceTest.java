package com.hic.service;

import com.hic.dto.EmployeeAttendanceRowDTO;
import com.hic.model.*;
import com.hic.model.DailyAttendanceSummary.AttendanceStatus;
import com.hic.repository.*;
import com.hic.util.AppTimeZone;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardAttendanceServiceTest {
    @Mock EmployeeRepository employees;
    @Mock BranchRepository branches;
    @Mock AttendanceLogRepository logs;
    @Mock AttendanceService attendance;
    @Mock AttendanceScheduleResolver scheduleResolver;
    @Mock UserScopeService userScope;
    @InjectMocks DashboardAttendanceService service;
    LocalDateTime now = LocalDateTime.of(2026, 10, 11, 17, 0);
    Employee one, two;

    @BeforeEach void setup() {
        TenantContext.setTenantId(7L);
        lenient().when(userScope.resolveBranchScope(null)).thenReturn(null);
        one = employee(1L, 10L); two = employee(2L, 20L);
        lenient().when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(7L)).thenReturn(List.of(one, two));
        Branch a = new Branch(); a.setId(10L); a.setName("Bakı"); a.setTenantId(7L);
        Branch b = new Branch(); b.setId(20L); b.setName("Sumqayıt"); b.setTenantId(7L);
        lenient().when(branches.findByTenantId(7L)).thenReturn(List.of(a, b));
        lenient().when(scheduleResolver.forReport(eq(7L), anyList(), any(), any())).thenReturn((e, d) ->
                new AttendanceScheduleResolver.DaySchedule(1L, "STANDARD", true, LocalTime.of(9,0), LocalTime.of(18,0),0,5,0));
        lenient().when(attendance.getEmployeeAttendanceForReport(any(), any(), any(), any()))
                .thenReturn(List.of(day("ABSENT")));
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void combinedSingleScanExpiresAtMidnightRatherThanDirectionalGraceWindow() {
        now = now.withHour(1);
        AttendanceLog previousDay = log(1, one, now.minusDays(1).withHour(23).withMinute(50), null, 10L);
        previousDay.setCombinedAreaId(10L);
        previousDay.setCombinedWorkDate(previousDay.getCheckInTime().toLocalDate());
        previousDay.setShiftType("STANDARD");
        source(previousDay);
        var result = service.at(now);
        assertThat(result.areas()).allSatisfy(area -> assertThat(area.employeeCount()).isZero());
        assertThat(result.attended()).isZero();
        assertThat(result.unresolvedPresence()).isEqualTo(1);
    }

    @Test void dayCountsAreDistinctAndIndependentWithDateSpecificLeaveAndPermission() {
        var present = day("EARLY_LEAVE"); present.setCheckInTime(AppTimeZone.toOffsetDateTime(now.withHour(9)));
        present.setLateMinutes(15); present.setEarlyLeaveMinutes(30); present.setHasPermission(true);
        when(attendance.getEmployeeAttendanceForReport(eq(one), any(), any(), any())).thenReturn(List.of(present));
        when(attendance.getEmployeeAttendanceForReport(eq(two), any(), any(), any())).thenReturn(List.of(day("ON_LEAVE")));
        source(log(1, one, now.withHour(9), now.withHour(12), 10L),
                log(2, one, now.withHour(13), now.withHour(16), 10L));
        var result = service.at(now);
        assertThat(result.attended()).isEqualTo(1);
        assertThat(result.absent()).isZero();
        assertThat(result.late()).isEqualTo(1); assertThat(result.earlyLeave()).isEqualTo(1);
        assertThat(result.permitted()).isEqualTo(1); assertThat(result.onLeave()).isEqualTo(1);
        assertThat(result.areas()).allSatisfy(area -> assertThat(area.employeeCount()).isZero());
    }

    @Test void latestEntryChoosesOneAreaAndLaterExitClosesOldOpenRows() {
        source(log(1, one, now.withHour(9), null, 10L), log(2, one, now.withHour(10), null, 10L),
                log(3, one, now.withHour(11), null, 20L));
        var result = service.at(now);
        assertThat(result.areas().get(0).employeeCount()).isZero();
        assertThat(result.areas().get(1).employeeCount()).isEqualTo(1);
        source(log(1, one, now.withHour(9), now.withHour(15), 10L),
                log(3, one, now.withHour(11), null, 20L));
        assertThat(service.at(now).areas()).allSatisfy(area -> assertThat(area.employeeCount()).isZero());
    }

    @Test void overnightPresenceCountsTodayAndStaleMissingExitDoesNotClaimCurrentPresence() {
        now = now.withHour(1);
        AttendanceLog overnight = log(1, one, now.minusDays(1).withHour(20), null, 20L); overnight.setShiftType("FLEX");
        source(overnight, log(2, two, now.minusDays(2).withHour(9), null, 10L));
        var result = service.at(now);
        assertThat(result.attended()).isEqualTo(1);
        assertThat(result.absent()).isZero(); // before the next scheduled start
        assertThat(result.areas().get(1).employeeCount()).isEqualTo(1);
        assertThat(result.unresolvedPresence()).isEqualTo(1);
    }

    @Test void unknownSnapshotIsNotAssignedToHomeAreaAndManualTimesWin() {
        AttendanceLog unknown = log(1, one, now.withHour(9), null, null);
        AttendanceLog manual = log(2, two, now.withHour(9), now.withHour(12), 20L);
        manual.setManualOverride(true); manual.setLastEntryTime(now.withHour(16));
        source(unknown, manual);
        var result = service.at(now);
        assertThat(result.unknownArea()).isEqualTo(1);
        assertThat(result.areas()).allSatisfy(area -> assertThat(area.employeeCount()).isZero());
    }

    @Test void scopeTenantEmploymentAndHireDateAreRespected() {
        Employee foreign = employee(3L, 10L); foreign.setTenantId(8L);
        Employee terminated = employee(4L, 10L); terminated.setEmploymentStatus(Employee.EmploymentStatus.TERMINATED);
        Employee future = employee(5L, 10L); future.setHireDate(now.toLocalDate().plusDays(1));
        when(employees.findAllByTenantIdOrderByFirstNameAscLastNameAsc(7L)).thenReturn(List.of(one, two, foreign, terminated, future));
        when(userScope.resolveBranchScope(null)).thenReturn(10L);
        source(log(1, one, now.withHour(9), null, 10L), log(2, two, now.withHour(9), null, 20L),
                log(3, foreign, now.withHour(9), null, 10L), log(4, terminated, now.withHour(9), null, 10L));
        var result = service.at(now);
        assertThat(result.attended()).isEqualTo(1);
        assertThat(result.areas()).hasSize(1);
        assertThat(result.areas().get(0).employeeCount()).isEqualTo(1);
        verify(attendance, times(1)).getEmployeeAttendanceForReport(any(), any(), any(), any());
    }

    @Test void absentExcludesRestDaysAndShiftNotStarted() {
        when(attendance.getEmployeeAttendanceForReport(eq(two), any(), any(), any())).thenReturn(List.of(day("DAY_OFF")));
        assertThat(service.at(now).absent()).isEqualTo(1);
        assertThat(service.at(now.withHour(8)).absent()).isZero();
    }

    private Employee employee(Long id, Long area) {
        Employee e = new Employee(); e.setId(id); e.setTenantId(7L); e.setBranchId(area);
        e.setEmploymentStatus(Employee.EmploymentStatus.ACTIVE); return e;
    }
    private EmployeeAttendanceRowDTO day(String status) {
        var row = new EmployeeAttendanceRowDTO(); row.setDate(now.toLocalDate()); row.setStatus(AttendanceStatus.valueOf(status)); return row;
    }
    private AttendanceLog log(long id, Employee employee, LocalDateTime in, LocalDateTime out, Long area) {
        AttendanceLog l = new AttendanceLog(); l.setId(id); l.setTenantId(employee.getTenantId()); l.setEmployeeId(employee.getId());
        l.setCheckInTime(in); l.setCheckOutTime(out); l.setAreaSnapshotId(area); l.setAreaSnapshotCaptured(true); return l;
    }
    private void source(AttendanceLog... values) {
        when(logs.findByTenantIdAndCheckInTimeBetween(eq(7L), any(), any())).thenReturn(List.of(values));
    }
}
