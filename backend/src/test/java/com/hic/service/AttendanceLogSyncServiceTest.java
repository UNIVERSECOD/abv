package com.hic.service;

import com.hic.dto.AttendanceLogSyncDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AttendanceLogSyncServiceTest {

    private AttendanceLogSyncService service;
    private MockRestServiceServer server;
    private com.hic.repository.DeviceConfigRepository devices;
    private com.hic.repository.EmployeeRepository employees;

    @org.junit.jupiter.api.AfterEach
    void clearTenant() { com.hic.util.TenantContext.clear(); }

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        com.hic.repository.EmployeeRepository employeeRepository = org.mockito.Mockito.mock(com.hic.repository.EmployeeRepository.class);
        employees = employeeRepository;
        com.hic.repository.DeviceConfigRepository deviceConfigRepository = org.mockito.Mockito.mock(com.hic.repository.DeviceConfigRepository.class);
        devices = deviceConfigRepository;
        service = new AttendanceLogSyncService(restTemplate, employeeRepository, deviceConfigRepository);
        ReflectionTestUtils.setField(service, "isapiBaseUrl", "http://host.docker.internal:8080");
    }

    @Test
    void bridgeIdWinsWhenItEqualsAnotherDevicesBackendId() {
        com.hic.util.TenantContext.setTenantId(1L);
        var combined = new com.hic.model.DeviceConfig();
        combined.setId(1L); combined.setDeviceId("101"); combined.setDoorRole("BOTH"); combined.setDeviceName("Combined fixture");
        var exit = new com.hic.model.DeviceConfig();
        exit.setId(101L); exit.setDeviceId("202"); exit.setDoorRole("EXIT"); exit.setDeviceName("Exit fixture");
        org.mockito.Mockito.when(devices.findByTenantId(1L)).thenReturn(List.of(combined, exit));
        server.expect(requestTo("http://host.docker.internal:8080/api/punches"))
                .andRespond(withSuccess("[{\"id\":15,\"deviceId\":101,\"employeeNo\":\"TEST\",\"punchTime\":\"2026-10-09T05:00:00Z\"}]", MediaType.APPLICATION_JSON));
        var rows = service.getAttendanceLogs(null, null, null);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDoorRole()).isEqualTo("BOTH");
        assertThat(rows.get(0).getDeviceName()).isEqualTo("Combined fixture");
        server.verify();
    }

    @Test
    void terminalIdentityTakesPrecedenceOverAnotherPersonsHrCode() {
        com.hic.util.TenantContext.setTenantId(1L);
        var own = new com.hic.model.DeviceConfig(); own.setId(1L); own.setDeviceId("101");
        org.mockito.Mockito.when(devices.findByTenantId(1L)).thenReturn(List.of(own));
        var hrMatch = new com.hic.model.Employee(); hrMatch.setEmployeeId("123"); hrMatch.setDeviceEmployeeNo("999"); hrMatch.setFirstName("Wrong HR match");
        var terminalMatch = new com.hic.model.Employee(); terminalMatch.setEmployeeId("456"); terminalMatch.setDeviceEmployeeNo("123"); terminalMatch.setFirstName("Correct terminal person");
        org.mockito.Mockito.when(employees.findByTenantIdAndEmployeeIdIn(1L, List.of("123"))).thenReturn(List.of(hrMatch));
        org.mockito.Mockito.when(employees.findByTenantIdAndDeviceEmployeeNoIgnoreCase(1L, "123")).thenReturn(List.of(terminalMatch));
        server.expect(requestTo("http://host.docker.internal:8080/api/punches"))
                .andRespond(withSuccess("[{\"id\":15,\"deviceId\":101,\"employeeNo\":\"123\",\"punchTime\":\"2026-10-09T05:00:00Z\"}]", MediaType.APPLICATION_JSON));
        assertThat(service.getAttendanceLogs(null, null, null).get(0).getFirstName()).isEqualTo("Correct terminal person");
        server.verify();
    }

    @Test
    void duplicateTerminalNumbersAreResolvedByAssignedDeviceWithoutPickingAnArbitraryPerson() {
        com.hic.util.TenantContext.setTenantId(1L);
        var device = new com.hic.model.DeviceConfig(); device.setId(1L); device.setTenantId(1L); device.setDeviceId("101");
        org.mockito.Mockito.when(devices.findByTenantId(1L)).thenReturn(List.of(device));
        var other = new com.hic.model.Employee(); other.setEmployeeId("HR-A"); other.setDeviceEmployeeNo("123"); other.setFirstName("Other area");
        var assigned = new com.hic.model.Employee(); assigned.setTenantId(1L); assigned.setEmployeeId("HR-B"); assigned.setDeviceEmployeeNo("123"); assigned.setFirstName("Assigned person");
        org.mockito.Mockito.when(employees.findByTenantIdAndDeviceEmployeeNoIgnoreCase(1L, "123")).thenReturn(List.of(other, assigned));
        org.mockito.Mockito.when(employees.findByDeviceAccessAndDeviceEmployeeNo(1L, "123")).thenReturn(List.of(assigned));
        server.expect(requestTo("http://host.docker.internal:8080/api/punches"))
                .andRespond(withSuccess("[{\"id\":15,\"deviceId\":101,\"employeeNo\":\"123\",\"punchTime\":\"2026-10-09T05:00:00Z\"}]", MediaType.APPLICATION_JSON));
        assertThat(service.getAttendanceLogs(null, null, null).get(0).getFirstName()).isEqualTo("Assigned person");
        server.verify();
    }

    @Test
    void backendIdCannotAuthorizeAnUnmappedBridgePunch() {
        com.hic.util.TenantContext.setTenantId(1L);
        var own = new com.hic.model.DeviceConfig(); own.setId(2L); own.setDeviceId("101");
        org.mockito.Mockito.when(devices.findByTenantId(1L)).thenReturn(List.of(own));
        server.expect(requestTo("http://host.docker.internal:8080/api/punches"))
                .andRespond(withSuccess("[{\"id\":15,\"deviceId\":2,\"employeeNo\":\"TEST\",\"punchTime\":\"2026-10-09T05:00:00Z\"}]", MediaType.APPLICATION_JSON));
        assertThat(service.getAttendanceLogs(null, null, null)).isEmpty();
        server.verify();
    }

    @Test
    void getAttendanceLogs_mapsIsapiPunchesResponse() {
        server.expect(requestTo("http://host.docker.internal:8080/api/punches"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":15,"deviceId":5,"employeeNo":"E-001","punchTime":"2026-05-08T10:20:30Z","rawEventId":42}]
                        """, MediaType.APPLICATION_JSON));

        List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> result = service.getAttendanceLogs(null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(15L);
        assertThat(result.get(0).getDeviceId()).isEqualTo(5L);
        assertThat(result.get(0).getEmployeeNo()).isEqualTo("E-001");
        assertThat(result.get(0).getRawEventId()).isEqualTo(42L);
        assertThat(result.get(0).getPunchTime()).isNotNull();
        server.verify();
    }

    @Test
    void getAttendanceLogs_includesOptionalQueryParams() {
        server.expect(requestTo("http://host.docker.internal:8080/api/punches?deviceId=9&employeeNo=123&limit=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<AttendanceLogSyncDTO.AttendanceLogEntryDTO> result = service.getAttendanceLogs(9L, " 123 ", 100);

        assertThat(result).isEmpty();
        server.verify();
    }
}
