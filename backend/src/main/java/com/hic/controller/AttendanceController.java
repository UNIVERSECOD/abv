package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.AttendanceDTO;
import com.hic.dto.AttendanceCorrectionRequest;
import com.hic.dto.AttendanceLogDTO;
import com.hic.dto.EmployeeAttendanceRowDTO;
import com.hic.dto.EmployeeAttendanceSummaryDTO;
import com.hic.dto.AttendanceReportRowDTO;
import com.hic.dto.DailyAttendanceSummaryDTO;
import com.hic.dto.DoorAttendanceSyncResultDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.dto.OpenAttendanceSessionDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.service.AttendanceCalculationService;
import com.hic.service.AttendanceReportService;
import com.hic.service.AttendanceService;
import com.hic.service.DoorAttendanceSyncService;
import com.hic.service.AttendanceSessionManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class AttendanceController {
    private final AttendanceService attendanceService;
    private final AttendanceCalculationService attendanceCalculationService;
    private final AttendanceReportService attendanceReportService;
    private final DoorAttendanceSyncService doorAttendanceSyncService;
    private final AttendanceSessionManagementService attendanceSessionManagementService;

    @PostMapping("/log")
    public ResponseEntity<ApiResponse<AttendanceLogDTO>> logAttendance(@Valid @RequestBody AttendanceDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.logAttendance(dto)));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<ApiResponse<?>> getLogsForEmployee(
            @PathVariable Long employeeId,
            @RequestParam String start,
            @RequestParam String end) {
        try {
            LocalDate parsedStart = LocalDate.parse(start);
            LocalDate parsedEnd = LocalDate.parse(end);
            List<EmployeeAttendanceRowDTO> rows = attendanceService.getEmployeeAttendance(employeeId, parsedStart, parsedEnd);
            return ResponseEntity.ok(ApiResponse.success(rows));
        } catch (DateTimeParseException ignored) {
            LocalDateTime parsedStart = LocalDateTime.parse(start);
            LocalDateTime parsedEnd = LocalDateTime.parse(end);
            List<AttendanceLogDTO> logs = attendanceService.getLogsForEmployee(employeeId, parsedStart, parsedEnd);
            return ResponseEntity.ok(ApiResponse.success(logs));
        }
    }

    @GetMapping("/range")
    public ResponseEntity<ApiResponse<List<AttendanceLogDTO>>> getLogsByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getLogsByDateRange(start, end)));
    }

    @GetMapping("/summary/{employeeId}")
    public ResponseEntity<ApiResponse<List<DailyAttendanceSummaryDTO>>> getSummary(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getDailySummary(employeeId, start, end)));
    }

    @GetMapping("/employee/{employeeId}/summary")
    public ResponseEntity<ApiResponse<EmployeeAttendanceSummaryDTO>> getEmployeeAttendanceSummary(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getEmployeeAttendanceSummary(employeeId, start, end)));
    }

    @PostMapping("/summary/generate/{employeeId}")
    public ResponseEntity<ApiResponse<DailyAttendanceSummaryDTO>> generateSummary(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.generateDailySummary(employeeId, date)));
    }

    @GetMapping("/report")
    public ResponseEntity<ApiResponse<PaginatedResponse<AttendanceReportRowDTO>>> getReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) String shiftType,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String fin,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String area,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(attendanceReportService.getReport(
                start, end, shiftType, employeeId, name, fin, position, department, area, page, size
        )));
    }

    @GetMapping("/report/export")
    public ResponseEntity<byte[]> exportReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) String shiftType,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String fin,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String area) {
        byte[] file = attendanceReportService.exportExcel(start, end, shiftType, employeeId, name, fin, position, department, area);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendance_reports.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PostMapping("/recalculate")
    public ResponseEntity<ApiResponse<Void>> recalculate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Long employeeId) {
        attendanceCalculationService.recalculate(start, end, employeeId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/open-sessions")
    public ResponseEntity<ApiResponse<PaginatedResponse<OpenAttendanceSessionDTO>>> getOpenSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new com.hic.exception.BadRequestException("Səhifə və ölçü yanlışdır");
        }
        return ResponseEntity.ok(ApiResponse.success(
                attendanceSessionManagementService.getOpenSessions(page, size)));
    }

    @PutMapping("/sessions/{attendanceLogId}")
    public ResponseEntity<ApiResponse<AttendanceLogDTO>> correctSession(
            @PathVariable Long attendanceLogId,
            @Valid @RequestBody AttendanceCorrectionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                attendanceSessionManagementService.correctSession(attendanceLogId, request)));
    }



    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<DoorAttendanceSyncResultDTO>> syncAllDevices(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                doorAttendanceSyncService.syncAllDevices(start, end, limit)
        ));
    }
}
