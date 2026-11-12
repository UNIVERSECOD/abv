package com.hic.service;

import com.hic.exception.BadRequestException;
import com.hic.model.DeviceConfig;
import com.hic.model.Employee;
import com.hic.repository.DeviceConfigRepository;
import com.hic.repository.EmployeeRepository;
import com.hic.util.TenantContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnprPlateTransferServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresSeparateConfirmationAndConsumesTheTokenOnce(boolean removal) {
        var employee = new Employee();
        employee.setFirstName("Sınaq");
        employee.setLastName("Əməkdaş");
        var camera = new DeviceConfig();
        camera.setDeviceId("103");
        camera.setTenantId(1L);
        camera.setDeviceIp("192.0.2.10");
        camera.setDevicePort(8080);
        camera.setDeviceName("Sınaq kamerası");
        camera.setDoorRole("ENTRY");
        camera.setAnpr(true);
        camera.setStatus("ACTIVE");
        var employees = mock(EmployeeRepository.class);
        var vehicles = mock(EmployeeVehicleService.class);
        var cameras = mock(DeviceConfigRepository.class);
        when(employees.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(employee));
        var assignments = List.of(new EmployeeVehicleService.Vehicle(5L, "10AB123", null));
        if (removal) when(vehicles.retired(1L, 7L)).thenReturn(assignments);
        else when(vehicles.list(1L, 7L)).thenReturn(assignments);
        when(cameras.findById(12L)).thenReturn(Optional.of(camera));
        var bridge = new RestTemplate();
        var server = MockRestServiceServer.bindTo(bridge).build();
        var service = new AnprPlateTransferService(employees, vehicles, cameras, bridge, "http://isapi");
        var fingerprint = "a".repeat(64);
        var url = "http://isapi/api/anpr/cameras/103/plate-transfer/";
        server.expect(requestTo(url + "preview")).andExpect(header("X-Allowed-Device-Ids", "103"))
                .andExpect(jsonPath("$.confirmed").value(false))
                .andExpect(jsonPath("$.removal").value(removal))
                .andRespond(withSuccess((removal ? """
                        {"recordId":"7","listType":"allowList","fingerprint":"%s"}
                        """ : """
                        {"operation":"add","listType":"allowList","fingerprint":"%s"}
                        """).formatted(fingerprint), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url + "apply")).andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.endpoint").value("192.0.2.10:8080"))
                .andExpect(jsonPath("$.fullName").value("Sınaq Əməkdaş"))
                .andExpect(jsonPath("$.plate").value("10AB123"))
                .andExpect(jsonPath("$.removal").value(removal))
                .andExpect(jsonPath("$.fingerprint").value(fingerprint))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        TenantContext.setUserId(20L);
        try {
            var token = removal ? service.previewRemoval(1L, 7L, 5L, 12L).token()
                    : service.preview(1L, 7L, 5L, 12L).token();
            java.util.function.Consumer<Boolean> confirm = confirmed -> {
                if (removal) service.confirmRemoval(1L, 7L, 5L, 12L, token, confirmed);
                else service.confirm(1L, 7L, 5L, token, confirmed);
            };
            assertThatThrownBy(() -> confirm.accept(false)).isInstanceOf(BadRequestException.class);
            TenantContext.setUserId(21L);
            assertThatThrownBy(() -> confirm.accept(true)).isInstanceOf(BadRequestException.class);
            TenantContext.setUserId(20L);
            if (removal) {
                when(vehicles.retired(1L, 7L)).thenReturn(List.of());
                assertThatThrownBy(() -> confirm.accept(true)).isInstanceOf(BadRequestException.class);
                when(vehicles.retired(1L, 7L)).thenReturn(assignments);
            }
            confirm.accept(true);
            assertThatThrownBy(() -> confirm.accept(true)).isInstanceOf(BadRequestException.class);
            server.verify();
        } finally {
            TenantContext.clear();
        }
    }
}
