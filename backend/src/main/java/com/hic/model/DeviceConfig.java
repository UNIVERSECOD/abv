package com.hic.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "device_configs")
public class DeviceConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "device_ip", nullable = false)
    private String deviceIp;

    @Column(name = "device_port")
    private Integer devicePort;

    @Column(name = "username")
    private String username;

    @Column(name = "password_encrypted")
    private String passwordEncrypted;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "door_id")
    private Long doorId;

    @Column(name = "door_role")
    private String doorRole;

    @Column(nullable = false)
    private boolean anpr;

    /** Prevent reinterpreting old punches after switching to/from combined mode or moving it. */
    @Column(name = "attendance_role_since")
    private LocalDateTime attendanceRoleSince;

    @Column(name = "status")
    private String status;

    @Column(name = "last_sync_time")
    private LocalDateTime lastSyncTime;

    @Column(name = "online", nullable = false)
    private boolean online;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
