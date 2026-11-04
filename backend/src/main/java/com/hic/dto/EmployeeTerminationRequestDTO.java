package com.hic.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeTerminationRequestDTO {
    private LocalDate terminationDate;
}
