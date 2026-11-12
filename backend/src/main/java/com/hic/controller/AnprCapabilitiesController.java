package com.hic.controller;

import com.hic.dto.AnprCapabilitiesDTO;
import com.hic.dto.ApiResponse;
import com.hic.service.AnprCapabilitiesService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/devices/{id}/anpr-capabilities")
@PreAuthorize("hasAnyRole('HEAD_OFFICE_HR','OFFICE_HR','DEPARTMENT_HR')")
public class AnprCapabilitiesController {
    private final AnprCapabilitiesService service;

    @PostMapping
    public ApiResponse<AnprCapabilitiesDTO> check(@PathVariable long id) {
        return ApiResponse.success(service.check(id));
    }
}
