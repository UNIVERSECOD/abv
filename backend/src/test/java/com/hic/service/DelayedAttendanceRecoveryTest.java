package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO;
import com.hic.model.*;
import com.hic.repository.*;
import com.hic.util.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DelayedAttendanceRecoveryTest {
    final AttendanceLogRepository logs = mock(AttendanceLogRepository.class);
    final AttendanceLogSyncService source = mock(AttendanceLogSyncService.class);
    final EmployeeRepository employees = mock(EmployeeRepository.class);
    final DeviceConfigRepository devices = mock(DeviceConfigRepository.class);
    final AttendanceCalculationService calculations = mock(AttendanceCalculationService.class);
    final AttendanceService summaries = mock(AttendanceService.class);
    final EmployeeShiftResolver shifts = mock(EmployeeShiftResolver.class);
    final AttendanceSessionPolicy policy = mock(AttendanceSessionPolicy.class);
    final List<AttendanceLog> stored = new ArrayList<>();
    final LocalDate day = LocalDate.of(2026, 10, 9);
    DoorAttendanceSyncService service;

    @BeforeEach void setup() {
        TenantContext.setTenantId(1L);
        service = new DoorAttendanceSyncService(source, logs, employees, devices, calculations, summaries,
                shifts, policy, mock(CombinedDeviceAttendanceService.class), mock(AnprAttendanceService.class));
        var employee = new Employee(); employee.setId(5L); employee.setTenantId(1L); employee.setDeviceEmployeeNo("test-5");
        when(devices.findByTenantId(1L)).thenReturn(List.of(device(10L, "101", "ENTRY"), device(11L, "102", "EXIT")));
        when(employees.findByDeviceAccessAndDeviceEmployeeNo(anyLong(), eq("test-5"))).thenReturn(List.of(employee));
        when(employees.findByTenantIdAndId(1L, 5L)).thenReturn(Optional.of(employee));
        when(shifts.resolve(any(), any())).thenReturn(new EmployeeShiftResolver.ResolvedShift(null, "FLEXIBLE", true));
        when(logs.findDirectionalSessionAt(eq(1L), eq(5L), any())).thenAnswer(i -> stored.stream()
                .filter(l -> !l.getCheckInTime().isAfter(i.getArgument(2)))
                .max(Comparator.comparing(AttendanceLog::getCheckInTime)));
        when(logs.findByTenantIdAndEntryPunchId(eq(1L), anyLong())).thenAnswer(i -> stored.stream()
                .filter(l -> Objects.equals(l.getEntryPunchId(), i.getArgument(1))).findFirst());
        when(logs.existsByTenantIdAndExitPunchId(eq(1L), anyLong())).thenAnswer(i -> stored.stream()
                .anyMatch(l -> Objects.equals(l.getExitPunchId(), i.getArgument(1))));
        when(logs.findByTenantIdAndEmployeeIdAndCheckInTime(eq(1L), eq(5L), any())).thenAnswer(i -> stored.stream()
                .filter(l -> l.getCheckInTime().equals(i.getArgument(2))).findFirst());
        when(logs.save(any())).thenAnswer(i -> {
            AttendanceLog row = i.getArgument(0);
            if (row.getId() == null) { row.setId(100L + stored.size()); stored.add(row); }
            return row;
        });
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void delayedPairAndReplayPreserveNewerOpenRow() {
        var newer = saved(day.plusDays(1).atTime(9, 0), null);
        input(List.of(punch(1,101,day.atTime(9,0))), List.of(punch(2,102,day.atTime(18,0))));
        assertThat(run().getMatchedSessions()).isEqualTo(1);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(1).getCheckOutTime()).isEqualTo(day.atTime(18,0));
        assertThat(newer.getCheckOutTime()).isNull();
        assertThat(run().getCreatedLogs()).isZero();
        verify(calculations).calculateForDay(5L, day);
    }

    @Test void delayedExitOnlyFindsOldEntryEvenWithNewerOpenEntry() {
        var old = saved(day.atTime(20, 0), null); old.setShiftType("FLEXIBLE");
        var newer = saved(day.plusDays(1).atTime(9, 0), null);
        input(List.of(), List.of(punch(2,102,day.plusDays(1).atTime(2,0))));
        assertThat(run().getMatchedSessions()).isEqualTo(1);
        assertThat(old.getCheckOutTime()).isEqualTo(day.plusDays(1).atTime(2,0));
        assertThat(newer.getCheckOutTime()).isNull();
        assertThat(run().getCreatedLogs()).isZero();
        verify(calculations).calculateForDay(5L, day.plusDays(1));
    }

    @Test void closedBoundaryPreventsDuplicateExitClosingAnOlderOpenRow() {
        var old = saved(day.atTime(8,0),null);
        saved(day.atTime(9,0),day.atTime(17,0));
        input(List.of(), List.of(punch(2,102,day.atTime(18,0))));
        assertThat(run().getMatchedSessions()).isZero();
        assertThat(old.getCheckOutTime()).isNull();
    }

    @Test void manualCorrectionIsNeverRewritten() {
        var manual = saved(day.atTime(9,0),day.atTime(18,0)); manual.setManualOverride(true);
        input(List.of(punch(1,101,day.atTime(10,0))), List.of(punch(2,102,day.atTime(17,0))));
        assertThat(run().getCreatedLogs()).isZero(); assertThat(stored).hasSize(1);
        assertThat(manual.getCheckOutTime()).isEqualTo(day.atTime(18,0));
    }

    private com.hic.dto.DoorAttendanceSyncResultDTO run() {
        return service.syncAllDevices(day.atStartOfDay(), day.plusDays(2).atStartOfDay(), 100);
    }
    private AttendanceLog saved(LocalDateTime in, LocalDateTime out) {
        var row = new AttendanceLog(); row.setId(1L + stored.size()); row.setTenantId(1L); row.setEmployeeId(5L);
        row.setCheckInTime(in); row.setCheckOutTime(out); row.setDeviceId("101"); stored.add(row); return row;
    }
    private void input(List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> entry, List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> exit) {
        when(source.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt())).thenReturn(entry);
        when(source.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt())).thenReturn(exit);
    }
    private DeviceConfig device(Long id, String bridge, String role) {
        var d = new DeviceConfig(); d.setId(id); d.setTenantId(1L); d.setDeviceId(bridge); d.setDoorRole(role); d.setStatus("ACTIVE"); return d;
    }
    private AttendanceLogSyncDTO.AttendanceLogEntryDTO punch(long id, long device, LocalDateTime time) {
        var p = new AttendanceLogSyncDTO.AttendanceLogEntryDTO(); p.setId(id); p.setDeviceId(device);
        p.setEmployeeNo("test-5"); p.setPunchTime(time.atZone(AppTimeZone.ZONE).toOffsetDateTime()); return p;
    }
}
