package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO;
import com.hic.dto.DoorAttendanceSyncResultDTO;
import com.hic.model.AttendanceLog;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.repository.AttendanceLogRepository;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoorAttendanceSyncServiceTest {

    @Mock private AttendanceLogSyncService attendanceLogSyncService;
    @Mock private AttendanceLogRepository attendanceLogRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private DeviceConfigRepository deviceConfigRepository;
    @Mock private AttendanceCalculationService attendanceCalculationService;
    @Mock private AttendanceService attendanceService;
    @Mock private EmployeeShiftResolver employeeShiftResolver;
    @Mock private AttendanceSessionPolicy attendanceSessionPolicy;
    @Mock private CombinedDeviceAttendanceService combinedDeviceAttendanceService;

    @InjectMocks
    private DoorAttendanceSyncService doorAttendanceSyncService;

    private final AtomicLong logIdSeq = new AtomicLong(1);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"not-a-bridge-id"})
    void invalidBridgeMappingCannotFallBackToBackendId(String bridgeId) {
        DeviceConfig invalid = device(101L, bridgeId, "BOTH");
        invalid.setBranchId(7L);
        when(deviceConfigRepository.findByTenantId(1L)).thenReturn(List.of(invalid));
        var result = doorAttendanceSyncService.syncAllDevices(
                LocalDate.of(2026, 10, 9).atStartOfDay(), LocalDate.of(2026, 10, 10).atStartOfDay(), 100);
        assertThat(result.getTotalPunches()).isZero();
        org.mockito.Mockito.verifyNoInteractions(attendanceLogSyncService, combinedDeviceAttendanceService);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        logIdSeq.set(1);
        // Model the event-time DB lookup using rows saved earlier in this test run.
        lenient().when(attendanceLogRepository.findDirectionalSessionAt(eq(1L), anyLong(), any()))
                .thenAnswer(call -> org.mockito.Mockito.mockingDetails(attendanceLogRepository).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("save"))
                        .map(i -> (AttendanceLog) i.getArgument(0))
                        .filter(l -> l.getCheckInTime() != null && !l.getCheckInTime().isAfter(call.getArgument(2)))
                        .max(java.util.Comparator.comparing(AttendanceLog::getCheckInTime)));
        lenient().when(employeeShiftResolver.resolve(any(), any()))
                .thenReturn(new EmployeeShiftResolver.ResolvedShift(null, "FLEXIBLE", true));
        lenient().when(attendanceSessionPolicy.isDuplicateEntry(any(), any())).thenAnswer(invocation -> {
            AttendanceLog open = invocation.getArgument(0);
            LocalDateTime punchTime = invocation.getArgument(1);
            return Math.abs(java.time.Duration.between(open.getCheckInTime(), punchTime).getSeconds()) <= 60;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void syncAllDevices_noActiveEntryExitDevices_returnsEmptyResult() {
        when(deviceConfigRepository.findByTenantId(1L)).thenReturn(List.of());

        DoorAttendanceSyncResultDTO result = doorAttendanceSyncService.syncAllDevices(
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 2, 0, 0),
                100);

        assertThat(result.getTotalPunches()).isZero();
        assertThat(result.getMatchedSessions()).isZero();
        assertThat(result.getCreatedLogs()).isZero();
        assertThat(result.getSkippedEmployees()).isZero();
        assertThat(result.getUnresolvedEmployeeNos()).isEmpty();
    }

    @Test
    void syncAllDevices_laterEntry_preservesMissingExitAndStartsNewSession() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");

        stubDevicesAndEmployee(entryDevice, exitDevice, employee);
        when(attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTime(eq(1L), eq(5L), any()))
                .thenReturn(Optional.empty());
        when(attendanceLogRepository.save(any(AttendanceLog.class))).thenAnswer(invocation -> {
            AttendanceLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(logIdSeq.getAndIncrement());
            }
            return log;
        });

        LocalDate day = LocalDate.of(2026, 7, 1);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(
                        punch("1001", 101L, day.atTime(9, 0)),
                        punch("1001", 101L, day.atTime(9, 20))
                ));
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of());

        doorAttendanceSyncService.syncAllDevices(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);

        ArgumentCaptor<AttendanceLog> saved = ArgumentCaptor.forClass(AttendanceLog.class);
        verify(attendanceLogRepository, atLeastOnce()).save(saved.capture());
        List<AttendanceLog> logs = saved.getAllValues();

        assertThat(logs).hasSizeGreaterThanOrEqualTo(3);
        AttendanceLog finalOpen = logs.get(logs.size() - 1);
        assertThat(finalOpen.getCheckInTime()).isEqualTo(day.atTime(9, 20));
        assertThat(finalOpen.getCheckOutTime()).isNull();
        assertThat(finalOpen.getId()).isNotEqualTo(logs.get(0).getId());
        assertThat(logs).anyMatch(log -> day.atTime(9, 0).equals(log.getCheckInTime())
                && "MISSING_EXIT".equals(log.getStatus()));
    }

    @Test
    void syncAllDevices_duplicateExit_doesNotExtendClosedSession() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");

        stubDevicesAndEmployee(entryDevice, exitDevice, employee);
        when(attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTime(eq(1L), eq(5L), any()))
                .thenReturn(Optional.empty());
        when(attendanceLogRepository.save(any(AttendanceLog.class))).thenAnswer(invocation -> {
            AttendanceLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(logIdSeq.getAndIncrement());
            }
            return log;
        });

        LocalDate day = LocalDate.of(2026, 7, 1);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 101L, day.atTime(9, 0))));
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(
                        punch("1001", 102L, day.atTime(17, 0)),
                        punch("1001", 102L, day.atTime(17, 30))
                ));

        doorAttendanceSyncService.syncAllDevices(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);

        ArgumentCaptor<AttendanceLog> saved = ArgumentCaptor.forClass(AttendanceLog.class);
        verify(attendanceLogRepository, atLeastOnce()).save(saved.capture());
        List<AttendanceLog> logs = saved.getAllValues();

        AttendanceLog closed = logs.get(logs.size() - 1);
        assertThat(closed.getCheckInTime()).isEqualTo(day.atTime(9, 0));
        assertThat(closed.getCheckOutTime()).isEqualTo(day.atTime(17, 0));
    }

    @Test
    void syncAllDevices_entryExitPair_closesSessionNormally() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");

        stubDevicesAndEmployee(entryDevice, exitDevice, employee);
        when(attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTime(eq(1L), eq(5L), any()))
                .thenReturn(Optional.empty());
        when(attendanceLogRepository.save(any(AttendanceLog.class))).thenAnswer(invocation -> {
            AttendanceLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(logIdSeq.getAndIncrement());
            }
            return log;
        });

        LocalDate day = LocalDate.of(2026, 7, 1);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 101L, day.atTime(9, 0))));
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 102L, day.atTime(18, 0))));

        DoorAttendanceSyncResultDTO result = doorAttendanceSyncService.syncAllDevices(
                day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);

        assertThat(result.getMatchedSessions()).isEqualTo(1);
        ArgumentCaptor<AttendanceLog> saved = ArgumentCaptor.forClass(AttendanceLog.class);
        verify(attendanceLogRepository, atLeastOnce()).save(saved.capture());
        AttendanceLog last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getCheckInTime()).isEqualTo(day.atTime(9, 0));
        assertThat(last.getCheckOutTime()).isEqualTo(day.atTime(18, 0));
        verify(attendanceCalculationService).calculateForDay(eq(5L), eq(day));
        verify(attendanceService).generateDailySummary(eq(5L), eq(day));
    }

    @Test
    void syncAllDevices_overnightFlexibleExit_recalculatesBothCalendarDays() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");

        stubDevicesAndEmployee(entryDevice, exitDevice, employee);
        when(attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTime(eq(1L), eq(5L), any()))
                .thenReturn(Optional.empty());
        when(attendanceLogRepository.save(any(AttendanceLog.class))).thenAnswer(invocation -> {
            AttendanceLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId(logIdSeq.getAndIncrement());
            }
            return log;
        });

        LocalDate day = LocalDate.of(2026, 7, 1);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 101L, day.atTime(20, 0))));
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 102L, day.plusDays(1).atTime(4, 0))));

        DoorAttendanceSyncResultDTO result = doorAttendanceSyncService.syncAllDevices(
                day.atStartOfDay(), day.plusDays(2).atStartOfDay(), 100);

        assertThat(result.getMatchedSessions()).isEqualTo(1);
        verify(attendanceCalculationService).calculateForDay(5L, day);
        verify(attendanceService).generateDailySummary(5L, day);
        verify(attendanceCalculationService).calculateForDay(5L, day.plusDays(1));
        verify(attendanceService).generateDailySummary(5L, day.plusDays(1));
    }

    @Test
    void syncAllDevices_expiredExit_doesNotCloseOldSession() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");
        stubDevicesAndEmployee(entryDevice, exitDevice, employee);

        LocalDate day = LocalDate.of(2026, 7, 1);
        AttendanceLog oldOpen = new AttendanceLog();
        oldOpen.setId(50L);
        oldOpen.setTenantId(1L);
        oldOpen.setEmployeeId(5L);
        oldOpen.setCheckInTime(day.atTime(9, 0));
        oldOpen.setStatus("OPEN");

        when(attendanceLogRepository
                .findDirectionalSessionAt(eq(1L), eq(5L), any()))
                .thenReturn(Optional.of(oldOpen));
        when(attendanceSessionPolicy.isExpired(eq(employee), eq(oldOpen), any())).thenReturn(true);
        when(attendanceLogRepository.save(oldOpen)).thenReturn(oldOpen);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of());
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 102L, day.plusDays(1).atTime(8, 0))));

        DoorAttendanceSyncResultDTO result = doorAttendanceSyncService.syncAllDevices(
                day.atStartOfDay(), day.plusDays(2).atStartOfDay(), 100);

        assertThat(result.getMatchedSessions()).isZero();
        assertThat(oldOpen.getCheckOutTime()).isNull();
        assertThat(oldOpen.getStatus()).isEqualTo("MISSING_EXIT");
        verify(attendanceCalculationService).calculateForDay(5L, day);
        verify(attendanceCalculationService, never()).calculateForDay(5L, day.plusDays(1));
    }

    @Test
    void syncAllDevices_sameTimestamp_ordersEntryBeforeExit() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");

        when(deviceConfigRepository.findByTenantId(1L)).thenReturn(List.of(exitDevice, entryDevice));
        when(employeeRepository.findByDeviceAccessAndDeviceEmployeeNo(anyLong(), eq("1001")))
                .thenReturn(List.of(employee));
        when(employeeRepository.findByTenantIdAndId(1L, employee.getId())).thenReturn(Optional.of(employee));
        when(attendanceLogRepository.findByTenantIdAndEmployeeIdAndCheckInTime(eq(1L), eq(5L), any()))
                .thenReturn(Optional.empty());
        when(attendanceLogRepository.save(any(AttendanceLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime punchTime = LocalDate.of(2026, 7, 1).atTime(9, 0);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 101L, punchTime)));
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 102L, punchTime)));

        DoorAttendanceSyncResultDTO result = doorAttendanceSyncService.syncAllDevices(
                punchTime.toLocalDate().atStartOfDay(), punchTime.toLocalDate().plusDays(1).atStartOfDay(), 100);

        assertThat(result.getMatchedSessions()).isZero();
        assertThat(result.getSkippedPunches()).isZero();
        ArgumentCaptor<AttendanceLog> saved = ArgumentCaptor.forClass(AttendanceLog.class);
        verify(attendanceLogRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(log -> punchTime.equals(log.getCheckInTime())
                && log.getCheckOutTime() == null);
    }

    @Test
    void syncAllDevices_exitWithoutEntry_isIgnored() {
        DeviceConfig entryDevice = device(10L, "101", "ENTRY");
        DeviceConfig exitDevice = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");

        stubDevicesAndEmployee(entryDevice, exitDevice, employee);

        LocalDate day = LocalDate.of(2026, 7, 1);
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of());
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 102L, day.atTime(18, 0))));

        doorAttendanceSyncService.syncAllDevices(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);

        verify(attendanceLogRepository, never()).save(any(AttendanceLog.class));
    }

    @Test
    void repeatedEntryUpdatesReportTimeAndReplayDoesNotReopenClosedVisit() {
        DeviceConfig entry = device(10L, "101", "ENTRY");
        DeviceConfig exit = device(11L, "102", "EXIT");
        Employee employee = employee(5L, "1001");
        stubDevicesAndEmployee(entry, exit, employee);
        LocalDate day = LocalDate.of(2026, 7, 1);
        AttendanceLog open = new AttendanceLog();
        open.setId(50L);
        open.setEmployeeId(5L);
        open.setTenantId(1L);
        open.setCheckInTime(day.atTime(9, 0, 50));
        open.setDeviceId("101");
        when(attendanceLogRepository
                .findDirectionalSessionAt(eq(1L), eq(5L), any()))
                .thenReturn(Optional.of(open));
        var repeated = punch("1001", 101L, day.atTime(9, 1, 10));
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(repeated));
        when(attendanceLogSyncService.getAttendanceLogs(eq(102L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(punch("1001", 102L, day.atTime(12, 0))));
        when(attendanceLogRepository.save(open)).thenReturn(open);

        var first = doorAttendanceSyncService.syncAllDevices(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);
        assertThat(first.getMatchedSessions()).isEqualTo(1);
        assertThat(open.getCheckInTime()).isEqualTo(day.atTime(9, 0, 50));
        assertThat(open.getLastEntryTime()).isEqualTo(day.atTime(9, 1, 10));
        assertThat(open.getCheckOutTime()).isEqualTo(day.atTime(12, 0));

        when(attendanceLogRepository.isRepeatedEntryAlreadyClosed(1L, 5L, day.atTime(9, 1, 10)))
                .thenReturn(true);
        var replay = doorAttendanceSyncService.syncAllDevices(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);
        assertThat(replay.getCreatedLogs()).isZero();
        assertThat(replay.getMatchedSessions()).isZero();
        verify(attendanceLogRepository, never()).findByTenantIdAndEmployeeIdAndCheckInTime(any(), any(), any());
    }

    @Test
    void combinedRoleUsesBridgeIdsAndAreaAndSkipsPunchesBeforeRoleChange() {
        DeviceConfig device = device(10L, "101", "BOTH");
        device.setBranchId(7L);
        LocalDate day = LocalDate.of(2026, 10, 9);
        device.setAttendanceRoleSince(day.atTime(8, 0));
        Employee employee = employee(5L, "1001");
        when(deviceConfigRepository.findByTenantId(1L)).thenReturn(List.of(device));
        when(employeeRepository.findByDeviceAccessAndDeviceEmployeeNo(10L, "1001")).thenReturn(List.of(employee));
        when(employeeRepository.findByTenantIdAndId(1L, 5L)).thenReturn(Optional.of(employee));
        var old = punch("1001", 101L, day.atTime(7, 59));
        var current = punch("1001", 101L, day.atTime(9, 0));
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(old, current));
        when(combinedDeviceAttendanceService.accept(1L, employee, 7L, current))
                .thenReturn(new CombinedDeviceAttendanceService.Result(true, false, false));
        var result = doorAttendanceSyncService.syncAllDevices(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);
        assertThat(result.getCreatedLogs()).isEqualTo(1);
        assertThat(result.getRecalculatedDays()).isEqualTo(1);
        verify(combinedDeviceAttendanceService, never()).accept(1L, employee, 7L, old);
        verify(attendanceCalculationService).calculateForDay(5L, day);
        verify(attendanceService).generateDailySummary(5L, day);
        verify(attendanceLogRepository, never()).save(any());
    }

    @Test
    void importBackfillIncludesYesterdayPunchesBeforeCurrentCombinedRoleAssignment() {
        DeviceConfig device = device(10L, "101", "BOTH");
        device.setBranchId(7L);
        LocalDate day = LocalDate.of(2026, 10, 9);
        device.setAttendanceRoleSince(day.atTime(8, 0));
        Employee employee = employee(5L, "1001");
        when(deviceConfigRepository.findByTenantId(1L)).thenReturn(List.of(device));
        when(employeeRepository.findByDeviceAccessAndDeviceEmployeeNo(10L, "1001")).thenReturn(List.of(employee));
        when(employeeRepository.findByTenantIdAndId(1L, 5L)).thenReturn(Optional.of(employee));
        var earlier = punch("1001", 101L, day.atTime(7, 59));
        var later = punch("1001", 101L, day.atTime(17, 0));
        when(attendanceLogSyncService.getAttendanceLogs(eq(101L), isNull(), any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(earlier, later));
        when(combinedDeviceAttendanceService.accept(1L, employee, 7L, earlier))
                .thenReturn(new CombinedDeviceAttendanceService.Result(true, false, false));
        when(combinedDeviceAttendanceService.accept(1L, employee, 7L, later))
                .thenReturn(new CombinedDeviceAttendanceService.Result(true, true, false));

        var result = doorAttendanceSyncService.syncAllDevicesForImport(
                day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 100);

        assertThat(result.getCreatedLogs()).isEqualTo(2);
        assertThat(result.getMatchedSessions()).isEqualTo(1);
        verify(combinedDeviceAttendanceService).accept(1L, employee, 7L, earlier);
        verify(combinedDeviceAttendanceService).accept(1L, employee, 7L, later);
    }

    private void stubDevicesAndEmployee(DeviceConfig entryDevice, DeviceConfig exitDevice, Employee employee) {
        when(deviceConfigRepository.findByTenantId(1L)).thenReturn(List.of(entryDevice, exitDevice));
        when(employeeRepository.findByDeviceAccessAndDeviceEmployeeNo(anyLong(), eq("1001")))
                .thenReturn(List.of(employee));
        when(employeeRepository.findByTenantIdAndId(1L, employee.getId())).thenReturn(Optional.of(employee));
    }

    private static DeviceConfig device(Long backendId, String isapiId, String role) {
        DeviceConfig device = new DeviceConfig();
        device.setId(backendId);
        device.setTenantId(1L);
        device.setDeviceId(isapiId);
        device.setDeviceIp("10.0.0.1");
        device.setStatus("ACTIVE");
        device.setDoorRole(role);
        return device;
    }

    private static Employee employee(Long id, String deviceEmployeeNo) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setTenantId(1L);
        employee.setEmployeeId("EMP-" + id);
        employee.setDeviceEmployeeNo(deviceEmployeeNo);
        return employee;
    }

    private static AttendanceLogSyncDTO.AttendanceLogEntryDTO punch(String employeeNo, Long isapiDeviceId, LocalDateTime localTime) {
        AttendanceLogSyncDTO.AttendanceLogEntryDTO punch = new AttendanceLogSyncDTO.AttendanceLogEntryDTO();
        punch.setEmployeeNo(employeeNo);
        punch.setDeviceId(isapiDeviceId);
        punch.setPunchTime(localTime.atZone(com.hic.util.AppTimeZone.ZONE).toOffsetDateTime());
        return punch;
    }
}
