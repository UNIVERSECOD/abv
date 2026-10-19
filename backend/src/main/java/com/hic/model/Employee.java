package com.hic.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "employees")
public class Employee {

    public enum EmploymentStatus {
        ACTIVE, INACTIVE, ON_LEAVE, TERMINATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    /**
     * Raw person ID on Hikvision devices (ISAPI employeeNo).
     * Used for punch matching and device sync. May duplicate across branches
     * for different people; HR-facing {@link #employeeId} is branch-prefixed.
     */
    @Column(name = "device_employee_no")
    private String deviceEmployeeNo;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "gender")
    private String gender;

    @Column(name = "mobile_phone")
    private String mobilePhone;

    @Column(name = "email")
    private String email;

    @Column(name = "fin_number")
    private String finNumber;

    @Column(name = "face_id")
    private String faceId;

    @Column(name = "card_id")
    private String cardId;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "contract_number")
    private String contractNumber;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;

    @Column(name = "annual_leave_duration")
    private Integer annualLeaveDuration;

    @Column(name = "annual_leave_balance")
    private Integer annualLeaveBalance;

    @Column(name = "leave_balance_start_date", nullable = false)
    private LocalDate leaveBalanceStartDate = com.hic.util.AppTimeZone.today();

    @Column(name = "father_name")
    private String fatherName;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "salary", precision = 12, scale = 2)
    private BigDecimal salary;

    @Column(name = "hourly_rate", precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    @Column(name = "allowance")
    private String allowance;

    @Column(name = "emergency_contact")
    private String emergencyContact;

    @Column(name = "address")
    private String address;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "area")
    private String area;

    @Column(name = "shift_type")
    private String shiftType;

    @Column(name = "timetable_id")
    private Long timetableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false)
    private EmploymentStatus employmentStatus = EmploymentStatus.ACTIVE;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
