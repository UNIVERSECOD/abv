package com.hic.dto;

import com.hic.model.Employee.EmploymentStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EmployeeResponseDTO {
    private Long id;
    private String employeeId;
    /** Raw device person ID (ISAPI employeeNo). */
    private String deviceEmployeeNo;
    private String firstName;
    private String lastName;
    private LocalDate birthDate;
    private String gender;
    private String mobilePhone;
    private String email;
    private String finNumber;
    private String faceId;
    private String faceImageUrl;
    private String cardId;
    private String serialNumber;
    private String contractNumber;
    private Long branchId;
    private String branchName;
    private List<Long> areaIds;
    private List<String> areaNames;
    private Long departmentId;
    private String departmentName;
    private Long positionId;
    private String positionName;
    private LocalDate hireDate;
    private LocalDate contractEndDate;
    private LocalDate terminationDate;
    private Integer annualLeaveDuration;
    private Integer annualLeaveBalance;
    private LocalDate leaveBalanceStartDate;
    private String fatherName;
    private String groupName;
    private BigDecimal salary;
    private BigDecimal netSalary;
    private BigDecimal actualSalary;
    private Integer priorExperienceYears;
    private Integer priorExperienceMonths;
    private Integer priorExperienceDays;
    private BigDecimal hourlyRate;
    private String allowance;
    private String emergencyContact;
    private String address;
    private String registeredAddress;
    private String notes;
    private String area;
    private String shiftType;
    private Long timetableId;
    private List<Long> deviceIds;
    private List<String> doorAccess;
    private EmploymentStatus employmentStatus;
    private long pendingDeviceRemovals;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
