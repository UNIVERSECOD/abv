package com.hic.controller;

import com.hic.dto.LeaveRequestDTO;
import com.hic.dto.ApiResponse;
import com.hic.model.LeaveRequest.LeaveStatus;
import com.hic.model.LeaveType;
import com.hic.service.LeaveService;
import com.hic.service.LeaveTransferService;
import com.hic.dto.DataImportResultDTO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class LeaveController {
    private final LeaveService leaveService;
    private final LeaveTransferService leaveTransferService;

    @GetMapping(value = {"/export", "/template"})
    public ResponseEntity<byte[]> export(jakarta.servlet.http.HttpServletRequest request) {
        boolean template = request.getRequestURI().endsWith("/template");
        byte[] content = leaveTransferService.export(template);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"leaves" + (template ? "_template" : "") + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DataImportResultDTO>> importFile(@RequestParam("file") MultipartFile file) {
        DataImportResultDTO result = leaveTransferService.importFile(file);
        return ResponseEntity.ok(new ApiResponse<>(result.isSuccessful(),
                result.isSuccessful() ? result.getImportedRows() + " məzuniyyət import edildi" : "Xətalar var. Heç bir məlumat yazılmadı", result));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> update(@PathVariable Long id, @Valid @RequestBody LeaveRequestDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.update(id, dto)));
    }

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<LeaveType>>> getLeaveTypes() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getAllLeaveTypes()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<LeaveRequestDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getAll()));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<LeaveRequestDTO>>> getByEmployee(@PathVariable Long employeeId) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getByEmployee(employeeId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> create(@Valid @RequestBody LeaveRequestDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.create(dto)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<LeaveRequestDTO>> updateStatus(
            @PathVariable Long id,
            @RequestParam LeaveStatus status,
            @RequestParam(required = false) Long approvedBy) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.updateStatus(id, status, approvedBy)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        leaveService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
