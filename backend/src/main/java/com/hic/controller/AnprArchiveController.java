package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.exception.BadRequestException;
import com.hic.service.AnprArchiveJobs;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/transport-report/archive")
@RequiredArgsConstructor
@PreAuthorize("hasRole('HEAD_OFFICE_HR')")
public class AnprArchiveController {
    private final AnprArchiveJobs archive;

    public record Request(Long areaId, LocalDate from, LocalDate to) {}

    @PostMapping
    public ApiResponse<AnprArchiveJobs.State> readAndApply(@RequestBody Request request) {
        if (request.areaId() == null) throw new BadRequestException("Ərazi seçin.");
        return ApiResponse.success(archive.start(tenant(), request.areaId(), request.from(), request.to()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnprArchiveJobs.State> status(@PathVariable UUID id) {
        return ApiResponse.success(archive.status(tenant(), id));
    }

    private long tenant() {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null) throw new BadRequestException("Şirkət məlumatı tələb olunur.");
        return tenant;
    }
}
