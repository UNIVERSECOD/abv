package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.exception.BadRequestException;
import com.hic.service.EmployeeVehicleService;
import com.hic.service.AnprPlateTransferService;
import com.hic.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/employees/{employeeId}/vehicles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('HEAD_OFFICE_HR')")
public class EmployeeVehicleController {
    private final EmployeeVehicleService vehicles;
    private final AnprPlateTransferService transfers;

    public record VehicleRequest(String plate, LocalDate effectiveFrom) {}
    public record CameraRequest(long cameraId) {}
    public record Confirmation(UUID token, boolean confirmed) {}

    @PostMapping("/{vehicleId}/camera-transfer/preview")
    public ApiResponse<AnprPlateTransferService.Preview> preview(@PathVariable long employeeId,
            @PathVariable long vehicleId, @RequestBody CameraRequest request) {
        return ApiResponse.success(transfers.preview(tenant(), employeeId, vehicleId, request.cameraId()));
    }

    @PostMapping("/{vehicleId}/camera-transfer/confirm")
    public ApiResponse<Void> confirm(@PathVariable long employeeId, @PathVariable long vehicleId,
                                     @RequestBody Confirmation request) {
        transfers.confirm(tenant(), employeeId, vehicleId, request.token(), request.confirmed());
        return ApiResponse.success(null);
    }

    @GetMapping
    public ApiResponse<List<EmployeeVehicleService.Vehicle>> list(@PathVariable long employeeId) {
        return ApiResponse.success(vehicles.list(tenant(), employeeId));
    }

    @GetMapping("/retired")
    public ApiResponse<List<EmployeeVehicleService.Vehicle>> retired(@PathVariable long employeeId) {
        return ApiResponse.success(vehicles.retired(tenant(), employeeId));
    }

    @PostMapping("/{vehicleId}/camera-removal/{cameraId}/preview")
    public ApiResponse<AnprPlateTransferService.RemovalPreview> previewRemoval(@PathVariable long employeeId,
            @PathVariable long vehicleId, @PathVariable long cameraId) {
        return ApiResponse.success(transfers.previewRemoval(tenant(), employeeId, vehicleId, cameraId));
    }

    @PostMapping("/{vehicleId}/camera-removal/{cameraId}/confirm")
    public ApiResponse<Void> confirmRemoval(@PathVariable long employeeId, @PathVariable long vehicleId,
            @PathVariable long cameraId, @RequestBody Confirmation request) {
        transfers.confirmRemoval(tenant(), employeeId, vehicleId, cameraId, request.token(), request.confirmed());
        return ApiResponse.success(null);
    }

    @PostMapping
    public ApiResponse<EmployeeVehicleService.Vehicle> add(@PathVariable long employeeId, @RequestBody VehicleRequest request) {
        return ApiResponse.success(vehicles.add(tenant(), employeeId, request.plate(), request.effectiveFrom()));
    }

    @DeleteMapping("/{vehicleId}")
    public ApiResponse<Void> retire(@PathVariable long employeeId, @PathVariable long vehicleId) {
        vehicles.retire(tenant(), employeeId, vehicleId);
        return ApiResponse.success(null);
    }

    private long tenant() {
        Long tenant = TenantContext.getTenantId();
        if (tenant == null) throw new BadRequestException("Şirkət məlumatı tələb olunur.");
        return tenant;
    }
}
