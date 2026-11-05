package com.hic.repository;

import com.hic.model.Employee;
import com.hic.model.Employee.EmploymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.tenantId = :tenantId and e.id = :id")
    Optional<Employee> lockForLeave(@Param("tenantId") Long tenantId, @Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.tenantId = :tenantId and e.id = :id")
    Optional<Employee> lockForTermination(@Param("tenantId") Long tenantId, @Param("id") Long id);

    // Tenant-aware methods
    Page<Employee> findByTenantId(Long tenantId, Pageable pageable);
    Page<Employee> findByTenantIdAndEmploymentStatusNot(
            Long tenantId, EmploymentStatus excludedStatus, Pageable pageable);
    Page<Employee> findByEmploymentStatusNot(EmploymentStatus excludedStatus, Pageable pageable);
    Page<Employee> findByTenantIdAndBranchId(Long tenantId, Long branchId, Pageable pageable);

    @Query("""
            SELECT e FROM Employee e
            WHERE (:tenantId IS NULL OR e.tenantId = :tenantId)
              AND e.employmentStatus <> :excludedStatus
              AND (:status IS NULL OR e.employmentStatus = :status)
              AND (:departmentId IS NULL OR e.departmentId = :departmentId)
              AND (:branchId IS NULL OR e.branchId = :branchId OR EXISTS (
                    SELECT ea.id FROM EmployeeArea ea
                    WHERE ea.employeeId = e.id AND ea.branchId = :branchId
              ))
              AND (:shiftType IS NULL
                   OR (:shiftType = 'STANDARD' AND UPPER(COALESCE(e.shiftType, '')) IN ('MORNING','NIGHT','STANDARD'))
                   OR (:shiftType = 'FLEXIBLE' AND UPPER(COALESCE(e.shiftType, '')) IN ('FLEXIBLE','FIRST_ENTRY','SERBEST','FREE_SHIFT','FREE'))
                   OR UPPER(COALESCE(e.shiftType, '')) = UPPER(:shiftType))
              AND (:query IS NULL
                   OR LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(e.email) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<Employee> findActiveByFilters(
            @Param("tenantId") Long tenantId,
            @Param("excludedStatus") EmploymentStatus excludedStatus,
            @Param("query") String query,
            @Param("departmentId") Long departmentId,
            @Param("status") EmploymentStatus status,
            @Param("shiftType") String shiftType,
            @Param("branchId") Long branchId,
            Pageable pageable);

    @Query("""
            SELECT e FROM Employee e
            WHERE e.tenantId = :tenantId
              AND EXISTS (
                  SELECT ea.id FROM EmployeeArea ea
                  WHERE ea.employeeId = e.id AND ea.branchId = :branchId
              )
            """)
    Page<Employee> findByTenantIdAndAreaId(
            @Param("tenantId") Long tenantId,
            @Param("branchId") Long branchId,
            Pageable pageable);

    @Query("""
            SELECT e FROM Employee e
            WHERE e.tenantId = :tenantId
              AND e.employmentStatus <> :excludedStatus
              AND EXISTS (
                  SELECT ea.id FROM EmployeeArea ea
                  WHERE ea.employeeId = e.id AND ea.branchId = :branchId
              )
            """)
    Page<Employee> findByTenantIdAndAreaIdAndEmploymentStatusNot(
            @Param("tenantId") Long tenantId,
            @Param("branchId") Long branchId,
            @Param("excludedStatus") EmploymentStatus excludedStatus,
            Pageable pageable);

    List<Employee> findAllByTenantIdOrderByFirstNameAscLastNameAsc(Long tenantId);
    List<Employee> findAllByTenantIdAndEmploymentStatusNotOrderByFirstNameAscLastNameAsc(
            Long tenantId, EmploymentStatus excludedStatus);

    List<Employee> findByTenantIdAndDepartmentId(Long tenantId, Long departmentId);
    List<Employee> findByTenantIdAndDepartmentIdAndEmploymentStatusNot(
            Long tenantId, Long departmentId, EmploymentStatus excludedStatus);
    List<Employee> findByTenantIdAndShiftType(Long tenantId, String shiftType);

    List<Employee> findByTenantIdAndEmploymentStatus(Long tenantId, EmploymentStatus status);

    Optional<Employee> findByTenantIdAndFinNumber(Long tenantId, String finNumber);

    Optional<Employee> findByTenantIdAndEmployeeId(Long tenantId, String employeeId);
    Optional<Employee> findByTenantIdAndEmployeeIdIgnoreCase(Long tenantId, String employeeId);
    List<Employee> findByEmployeeIdIn(Collection<String> employeeIds);
    List<Employee> findByTenantIdAndEmployeeIdIn(Long tenantId, Collection<String> employeeIds);

    List<Employee> findByTenantIdAndDeviceEmployeeNoIgnoreCase(Long tenantId, String deviceEmployeeNo);
    List<Employee> findByDeviceEmployeeNoIgnoreCase(String deviceEmployeeNo);

    @Query("""
            SELECT e FROM Employee e, EmployeeDeviceAccess a
            WHERE a.employeeId = e.id
              AND a.deviceConfigId = :deviceConfigId
              AND LOWER(e.deviceEmployeeNo) = LOWER(:deviceEmployeeNo)
            """)
    List<Employee> findByDeviceAccessAndDeviceEmployeeNo(
            @Param("deviceConfigId") Long deviceConfigId,
            @Param("deviceEmployeeNo") String deviceEmployeeNo);

    @Query("""
            SELECT e FROM Employee e
            WHERE e.tenantId = :tenantId
              AND e.branchId = :branchId
              AND LOWER(e.deviceEmployeeNo) = LOWER(:deviceEmployeeNo)
            """)
    List<Employee> findByTenantIdAndBranchIdAndDeviceEmployeeNoIgnoreCase(
            @Param("tenantId") Long tenantId,
            @Param("branchId") Long branchId,
            @Param("deviceEmployeeNo") String deviceEmployeeNo);

    Optional<Employee> findByTenantIdAndId(Long tenantId, Long id);

    Page<Employee> findByTenantIdAndDepartmentIdIn(Long tenantId, Collection<Long> departmentIds, Pageable pageable);

    long countByTenantId(Long tenantId);
    long countByTenantIdAndBranchId(Long tenantId, Long branchId);

    long countByTenantIdAndEmploymentStatus(Long tenantId, EmploymentStatus status);

    long countByEmploymentStatus(EmploymentStatus status);

    @Query("SELECT e FROM Employee e WHERE e.tenantId = :tenantId AND (" +
           "LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.email) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Employee> searchByTenant(@Param("tenantId") Long tenantId,
                                   @Param("query") String query,
                                   Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.tenantId = :tenantId " +
           "AND (:branchId IS NULL OR e.branchId = :branchId OR EXISTS (" +
           "SELECT ea.id FROM EmployeeArea ea WHERE ea.employeeId = e.id AND ea.branchId = :branchId)) AND (" +
           "LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Employee> searchMinimalByTenant(@Param("tenantId") Long tenantId,
                                         @Param("branchId") Long branchId,
                                         @Param("query") String query,
                                         Pageable pageable);

    // Legacy non-tenant methods (backward compatibility)
    List<Employee> findByDepartmentId(Long departmentId);
    List<Employee> findByDepartmentIdAndEmploymentStatusNot(
            Long departmentId, EmploymentStatus excludedStatus);
    List<Employee> findByShiftType(String shiftType);
    List<Employee> findByEmploymentStatus(EmploymentStatus status);
    Optional<Employee> findByFinNumber(String finNumber);
    Optional<Employee> findByEmployeeId(String employeeId);
    Optional<Employee> findByEmployeeIdIgnoreCase(String employeeId);
    List<Employee> findByDepartmentIdIn(Collection<Long> departmentIds);
    Page<Employee> findByDepartmentIdIn(Collection<Long> departmentIds, Pageable pageable);
    Page<Employee> findByDepartmentIdInAndEmploymentStatusNot(
            Collection<Long> departmentIds, EmploymentStatus excludedStatus, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.tenantId = :tenantId " +
           "AND e.employmentStatus <> :excludedStatus AND (" +
           "LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.email) LIKE LOWER(CONCAT('%', :query, '%')))" )
    Page<Employee> searchByTenantAndEmploymentStatusNot(
            @Param("tenantId") Long tenantId,
            @Param("query") String query,
            @Param("excludedStatus") EmploymentStatus excludedStatus,
            Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE e.employmentStatus <> :excludedStatus AND (" +
           "LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.email) LIKE LOWER(CONCAT('%', :query, '%')))" )
    Page<Employee> searchByEmploymentStatusNot(
            @Param("query") String query,
            @Param("excludedStatus") EmploymentStatus excludedStatus,
            Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE " +
           "(:status IS NULL OR e.employmentStatus = :status) AND " +
           "(:departmentId IS NULL OR e.departmentId = :departmentId)")
    Page<Employee> findByFilters(@Param("status") EmploymentStatus status,
                                  @Param("departmentId") Long departmentId,
                                  Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE " +
           "LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.email) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Employee> search(@Param("query") String query, Pageable pageable);

    @Query("SELECT e FROM Employee e WHERE (:branchId IS NULL OR e.branchId = :branchId OR EXISTS (" +
           "SELECT ea.id FROM EmployeeArea ea WHERE ea.employeeId = e.id AND ea.branchId = :branchId)) AND (" +
           "LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(CONCAT(e.firstName, ' ', e.lastName)) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.employeeId) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(e.finNumber) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Employee> searchMinimal(@Param("branchId") Long branchId,
                                 @Param("query") String query,
                                 Pageable pageable);

    long countByDepartmentId(Long departmentId);

    long countByPositionId(Long positionId);

    @Query("SELECT DISTINCT e.area FROM Employee e WHERE e.tenantId = :tenantId AND e.area IS NOT NULL AND e.area <> '' ORDER BY e.area")
    List<String> findDistinctAreasByTenantId(@Param("tenantId") Long tenantId);
}
