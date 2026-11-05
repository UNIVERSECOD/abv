package com.hic.controller;

import com.hic.dto.EmployeeDTO;
import com.hic.dto.EmployeeResponseDTO;
import com.hic.dto.EmployeeTerminationResultDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.dto.ApiResponse;
import com.hic.model.Employee.EmploymentStatus;
import com.hic.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class EmployeeController {
    private final EmployeeService employeeService;

    @GetMapping
    public ResponseEntity<PaginatedResponse<EmployeeResponseDTO>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) EmploymentStatus status,
            @RequestParam(required = false) String shiftType) {
        return ResponseEntity.ok(employeeService.getAll(
                page, size, sortBy, branchId, q, departmentId, status, shiftType));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeResponseDTO>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getById(id)));
    }

    @GetMapping("/branch/{branchId}")
    public ResponseEntity<PaginatedResponse<EmployeeResponseDTO>> getByBranch(
            @PathVariable Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(employeeService.getByBranch(branchId, page, size));
    }

    @GetMapping("/department/{departmentId}")
    public ResponseEntity<ApiResponse<List<EmployeeResponseDTO>>> getByDepartment(@PathVariable Long departmentId) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getByDepartment(departmentId)));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<EmployeeResponseDTO>>> getByStatus(@PathVariable EmploymentStatus status) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getByStatus(status)));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ResponseEntity.ok(employeeService.search(q, page != null ? page : 0, size != null ? size : 20));
        }
        return ResponseEntity.ok(ApiResponse.success(employeeService.searchEmployees(q)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EmployeeResponseDTO>> create(@Valid @RequestBody EmployeeDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.create(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeResponseDTO>> update(@PathVariable Long id, @Valid @RequestBody EmployeeDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.update(id, dto)));
    }

    @PutMapping("/{id}/leave-balance")
    public ResponseEntity<ApiResponse<EmployeeResponseDTO>> updateLeaveBalance(
            @PathVariable Long id, @RequestParam Integer days) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.updateLeaveBalance(id, days)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        employeeService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/terminate")
    public ResponseEntity<ApiResponse<EmployeeTerminationResultDTO>> terminate(
            @PathVariable Long id,
            @RequestBody(required = false) com.hic.dto.EmployeeTerminationRequestDTO request) {
        return ResponseEntity.ok(ApiResponse.success(request == null
                ? employeeService.terminate(id)
                : employeeService.terminate(id, request.getTerminationDate())));
    }

    @GetMapping("/{id}/doors")
    public ResponseEntity<ApiResponse<List<String>>> getEmployeeDoors(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getEmployeeDoorAccess(id)));
    }

    @GetMapping("/areas")
    public ResponseEntity<ApiResponse<List<String>>> getDistinctAreas() {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getDistinctAreas()));
    }
}
