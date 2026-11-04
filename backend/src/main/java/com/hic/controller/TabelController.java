package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.dto.TabelMonthlyDTO;
import com.hic.service.TabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tabel")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class TabelController {

    private final TabelService tabelService;

    @GetMapping
    public ResponseEntity<ApiResponse<TabelMonthlyDTO>> getMonthly(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long positionId,
            @RequestParam(required = false) String search
    ) {
        return ResponseEntity.ok(ApiResponse.success("OK", tabelService.getMonthlyTabel(
                year, month, branchId, departmentId, positionId, search
        )));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportMonthly(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long positionId,
            @RequestParam(required = false) String search
    ) {
        byte[] file = tabelService.exportMonthlyTabel(year, month, branchId, departmentId, positionId, search);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendra-tabel.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }
}
