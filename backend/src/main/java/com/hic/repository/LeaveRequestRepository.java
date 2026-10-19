package com.hic.repository;

import com.hic.model.LeaveRequest;
import com.hic.model.LeaveRequest.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LeaveRequest r where r.id = :id and r.tenantId = :tenantId")
    java.util.Optional<LeaveRequest> lockInTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    java.util.Optional<LeaveRequest> findByIdAndTenantId(Long id, Long tenantId);

    // Tenant-aware methods
    List<LeaveRequest> findByTenantId(Long tenantId);
    List<LeaveRequest> findByTenantIdAndEmployeeId(Long tenantId, Long employeeId);
    List<LeaveRequest> findByTenantIdAndStatus(Long tenantId, LeaveStatus status);

    long countByTenantIdAndStatus(Long tenantId, LeaveStatus status);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.tenantId = :tenantId AND lr.employeeId IN :employeeIds " +
           "AND lr.startDate <= :end AND lr.endDate >= :start AND lr.status = 'APPROVED'")
    List<LeaveRequest> findApprovedByTenantAndEmployeeIdsAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("employeeIds") List<Long> employeeIds,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.tenantId = :tenantId AND lr.employeeId = :employeeId " +
           "AND lr.startDate <= :end AND lr.endDate >= :start AND lr.status = 'APPROVED'")
    List<LeaveRequest> findApprovedByTenantAndEmployeeIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("employeeId") Long employeeId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    // Legacy non-tenant methods
    List<LeaveRequest> findByEmployeeId(Long employeeId);
    List<LeaveRequest> findByStatus(LeaveStatus status);
    List<LeaveRequest> findByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.employeeId IN :employeeIds " +
           "AND lr.startDate <= :end AND lr.endDate >= :start")
    List<LeaveRequest> findByEmployeeIdsAndDateRange(
            @Param("employeeIds") List<Long> employeeIds,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);

    @Query("SELECT lr FROM LeaveRequest lr WHERE lr.employeeId = :employeeId " +
           "AND lr.startDate <= :end AND lr.endDate >= :start AND lr.status = 'APPROVED'")
    List<LeaveRequest> findApprovedByEmployeeIdAndDateRange(
            @Param("employeeId") Long employeeId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end);
}
