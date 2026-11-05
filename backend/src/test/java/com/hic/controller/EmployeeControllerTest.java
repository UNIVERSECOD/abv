package com.hic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hic.dto.EmployeeDTO;
import com.hic.dto.EmployeeResponseDTO;
import com.hic.dto.EmployeeTerminationResultDTO;
import com.hic.dto.PaginatedResponse;
import com.hic.exception.ResourceNotFoundException;
import com.hic.model.Employee.EmploymentStatus;
import com.hic.service.EmployeeService;
import com.hic.util.JwtUtil;
import com.hic.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = EmployeeController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class}
)
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private EmployeeService employeeService;

    private EmployeeResponseDTO buildResponseDTO() {
        EmployeeResponseDTO dto = new EmployeeResponseDTO();
        dto.setId(1L);
        dto.setEmployeeId("EMP202401001");
        dto.setFirstName("John");
        dto.setLastName("Doe");
        dto.setDepartmentId(1L);
        dto.setDepartmentName("Engineering");
        dto.setEmploymentStatus(EmploymentStatus.ACTIVE);
        dto.setHireDate(LocalDate.of(2024, 1, 1));
        return dto;
    }

    @Test
    void getAll_returnsPaginatedResponse() throws Exception {
        EmployeeResponseDTO dto = buildResponseDTO();
        PaginatedResponse<EmployeeResponseDTO> page = PaginatedResponse.of(List.of(dto), 1L, 1, 0, 20);
        when(employeeService.getAll(0, 20, null, null, null, null, null, null)).thenReturn(page);

        mockMvc.perform(get("/api/employees?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].firstName").value("John"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getById_existingEmployee_returnsEmployee() throws Exception {
        EmployeeResponseDTO dto = buildResponseDTO();
        when(employeeService.getById(1L)).thenReturn(dto);

        mockMvc.perform(get("/api/employees/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("John"))
                .andExpect(jsonPath("$.data.lastName").value("Doe"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(employeeService.getById(99L))
                .thenThrow(new ResourceNotFoundException("Employee", 99L));

        mockMvc.perform(get("/api/employees/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_validDTO_returns200() throws Exception {
        EmployeeDTO dto = new EmployeeDTO();
        dto.setFirstName("Jane");
        dto.setLastName("Smith");
        dto.setFinNumber("7ABC123");
        dto.setDepartmentId(1L);
        dto.setTimetableId(1L);

        EmployeeResponseDTO response = buildResponseDTO();
        response.setFirstName("Jane");
        when(employeeService.create(any(EmployeeDTO.class))).thenReturn(response);

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Jane"));
    }

    @Test
    void create_missingFirstName_returns400() throws Exception {
        // Send blank first name - @NotBlank should trigger
        String body = "{\"firstName\": \"\", \"lastName\": \"Smith\", \"departmentId\": 1}";

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingDepartment_returns400() throws Exception {
        // departmentId is null - @NotNull should trigger
        String body = "{\"firstName\": \"Jane\", \"lastName\": \"Smith\"}";

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingFinNumber_returns400() throws Exception {
        String body = "{\"firstName\": \"Jane\", \"lastName\": \"Smith\", \"departmentId\": 1, \"timetableId\": 1}";

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.finNumber").value("FIN daxil edilməlidir"));
    }

    @Test
    void update_existingEmployee_returns200() throws Exception {
        EmployeeDTO dto = new EmployeeDTO();
        dto.setFirstName("Updated");
        dto.setLastName("Name");
        dto.setFinNumber("7ABC123");
        dto.setDepartmentId(1L);
        dto.setTimetableId(1L);

        EmployeeResponseDTO response = buildResponseDTO();
        response.setFirstName("Updated");
        when(employeeService.update(eq(1L), any(EmployeeDTO.class))).thenReturn(response);

        mockMvc.perform(put("/api/employees/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Updated"));
    }

    @Test
    void delete_existingEmployee_returns200() throws Exception {
        doNothing().when(employeeService).delete(1L);

        mockMvc.perform(delete("/api/employees/1"))
                .andExpect(status().isOk());

        verify(employeeService).delete(1L);
    }

    @Test
    void terminate_existingEmployee_returnsPerDeviceResult() throws Exception {
        EmployeeTerminationResultDTO result = new EmployeeTerminationResultDTO(
                1L, 2, 1, 1, List.of("Çıxış terminalı: cihaz offline-dır"));
        when(employeeService.terminate(1L)).thenReturn(result);

        mockMvc.perform(post("/api/employees/1/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeId").value(1))
                .andExpect(jsonPath("$.data.removedDevices").value(1))
                .andExpect(jsonPath("$.data.failedDevices").value(1));

        verify(employeeService).terminate(1L);
    }
}
