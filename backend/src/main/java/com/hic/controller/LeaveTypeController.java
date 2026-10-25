package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.model.LeaveType;
import com.hic.service.LeaveTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leaves/types")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class LeaveTypeController {
    private final LeaveTypeService service;

    @PostMapping
    public ApiResponse<LeaveType> create(@RequestBody LeaveTypeService.Input input) {
        return ApiResponse.success(service.save(null, input));
    }

    @PutMapping("/{id}")
    public ApiResponse<LeaveType> update(@PathVariable Long id, @RequestBody LeaveTypeService.Input input) {
        return ApiResponse.success(service.save(id, input));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }
}
