package com.hic.dto;

import com.hic.model.LeaveRequest.LeaveStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LeaveRequestDTO {

    private Long id;
    private String employeeCode;
    private String employeeName;
    private String finNumber;
    private String leaveTypeName;

    @NotNull(message = "Employee ID is required")
    private Long employeeId;

    @NotNull(message = "Leave type is required")
    private Long leaveTypeId;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    private LeaveStatus status;
    private Long approvedBy;
    private LocalDate approvalDate;
    private Boolean deductFromLeaveBalance;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private Integer balanceChargedDays;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private boolean legacyBalanceAccounting;
    private LocalDateTime createdAt;
}
