package com.hic.controller;

import com.hic.dto.AnnualLeaveBalanceDTO;
import com.hic.dto.ApiResponse;
import com.hic.service.AnnualLeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/annual-leave")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class AnnualLeaveController {

    private final AnnualLeaveService annualLeaveService;
    private final com.hic.service.LeaveBalanceService leaveBalanceService;

    @GetMapping("/usage")
    public ApiResponse<List<com.hic.service.LeaveBalanceService.TypeUsage>> usage(@RequestParam(required = false) Long employeeId) {
        return ApiResponse.success(leaveBalanceService.getUsage(employeeId));
    }

    @GetMapping("/balances")
    public ResponseEntity<ApiResponse<List<com.hic.service.LeaveBalanceService.Balance>>> balances() {
        return ResponseEntity.ok(ApiResponse.success("Məzuniyyət balansları", leaveBalanceService.getAll()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AnnualLeaveBalanceDTO>>> getAll(
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "employeeId", required = false) Long employeeId
    ) {
        return ResponseEntity.ok(ApiResponse.success("Annual leave balances listed", annualLeaveService.getAll(year, employeeId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AnnualLeaveBalanceDTO>> create(@RequestBody AnnualLeaveBalanceDTO dto) {
        return ResponseEntity.ok(ApiResponse.success("Annual leave balance created", annualLeaveService.create(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AnnualLeaveBalanceDTO>> update(
            @PathVariable Long id,
            @RequestBody AnnualLeaveBalanceDTO dto
    ) {
        return ResponseEntity.ok(ApiResponse.success("Annual leave balance updated", annualLeaveService.update(id, dto)));
    }

    @PostMapping("/{employeeId}/{year}/recalculate")
    public ResponseEntity<ApiResponse<AnnualLeaveBalanceDTO>> recalculate(
            @PathVariable Long employeeId,
            @PathVariable Integer year
    ) {
        return ResponseEntity.ok(ApiResponse.success("Annual leave recalculated", annualLeaveService.recalculate(employeeId, year)));
    }
}
