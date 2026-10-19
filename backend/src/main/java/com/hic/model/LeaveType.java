package com.hic.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "leave_types")
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "leave_code", nullable = false)
    private String leaveCode;

    @Column(name = "leave_name", nullable = false)
    private String leaveName;

    public String displayName() {
        if ("ANNUAL".equalsIgnoreCase(leaveCode)) return "Ödənişli məzuniyyət";
        if ("UNPAID".equalsIgnoreCase(leaveCode)) return "Ödənişsiz məzuniyyət";
        return leaveName;
    }

    @Column(name = "annual_entitlement")
    private Integer annualEntitlement;

    @Column(name = "is_paid")
    private Boolean isPaid;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
