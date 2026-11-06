package com.hic.repository;

import com.hic.model.AttendanceLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AttendanceLogRepository extends JpaRepository<AttendanceLog, Long> {

    // Include closed/manual rows as boundaries: never jump over one to close an older open row.
    @Query(value = "select * from attendance_logs where tenant_id = :tenantId and employee_id = :employeeId "
            + "and check_in_time <= :time and combined_area_id is null "
            + "order by check_in_time desc, id desc limit 1", nativeQuery = true)
    java.util.Optional<AttendanceLog> findDirectionalSessionAt(@Param("tenantId") Long tenantId,
            @Param("employeeId") Long employeeId, @Param("time") LocalDateTime time);

    @Query("select a from AttendanceLog a where a.tenantId = :tenantId "
            + "and (:employeeId is null or a.employeeId = :employeeId) "
            + "and a.checkInTime < :start and a.checkOutTime > :start")
    List<AttendanceLog> findClosedCarryIn(@Param("tenantId") Long tenantId,
            @Param("employeeId") Long employeeId, @Param("start") LocalDateTime start);

    // Tenant-aware methods
    java.util.Optional<AttendanceLog> findByTenantIdAndEmployeeIdAndCombinedAreaIdAndCombinedWorkDate(
            Long tenantId, Long employeeId, Long areaId, java.time.LocalDate workDate);

    List<AttendanceLog> findByTenantIdAndCheckInTimeBetween(Long tenantId, LocalDateTime start, LocalDateTime end);
    List<AttendanceLog> findByTenantIdAndEmployeeIdAndCheckInTimeBetween(Long tenantId, Long employeeId,
                                                                          LocalDateTime start, LocalDateTime end);
    long countByTenantIdAndCheckInTimeBetween(Long tenantId, LocalDateTime start, LocalDateTime end);

    @Query("""
            select count(distinct attendance.employeeId)
            from AttendanceLog attendance
            where attendance.tenantId = :tenantId
              and attendance.checkInTime >= :start
              and attendance.checkInTime < :endExclusive
            """)
    long countDistinctEmployeesCheckedIn(@Param("tenantId") Long tenantId,
                                         @Param("start") LocalDateTime start,
                                         @Param("endExclusive") LocalDateTime endExclusive);

    @Query("""
            select count(distinct attendance.employeeId)
            from AttendanceLog attendance
            where attendance.checkInTime >= :start
              and attendance.checkInTime < :endExclusive
            """)
    long countDistinctEmployeesCheckedIn(@Param("start") LocalDateTime start,
                                         @Param("endExclusive") LocalDateTime endExclusive);

    java.util.Optional<AttendanceLog> findByTenantIdAndEmployeeIdAndCheckInTime(Long tenantId, Long employeeId, LocalDateTime checkInTime);
    java.util.Optional<AttendanceLog> findByEmployeeIdAndCheckInTime(Long employeeId, LocalDateTime checkInTime);
    java.util.Optional<AttendanceLog> findByTenantIdAndId(Long tenantId, Long id);
    java.util.Optional<AttendanceLog> findByTenantIdAndEntryPunchId(Long tenantId, Long entryPunchId);
    boolean existsByTenantIdAndExitPunchId(Long tenantId, Long exitPunchId);

    @Query("""
            select count(a) > 0 from AttendanceLog a
            where a.tenantId = :tenantId and a.employeeId = :employeeId
              and a.checkOutTime is not null
              and a.checkInTime < :punchTime and a.lastEntryTime >= :punchTime
            """)
    boolean isRepeatedEntryAlreadyClosed(@Param("tenantId") Long tenantId,
                                         @Param("employeeId") Long employeeId,
                                         @Param("punchTime") LocalDateTime punchTime);

    java.util.Optional<AttendanceLog> findFirstByTenantIdAndEmployeeIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(
            Long tenantId, Long employeeId);
    java.util.Optional<AttendanceLog> findFirstByEmployeeIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(Long employeeId);

    @Query(value = "select * from attendance_logs where tenant_id = :tenantId and employee_id = :employeeId "
            + "and check_out_time is null and manual_override = false and combined_area_id is null "
            + "order by check_in_time desc limit 1", nativeQuery = true)
    java.util.Optional<AttendanceLog> findFirstByTenantIdAndEmployeeIdAndCheckOutTimeIsNullAndManualOverrideFalseOrderByCheckInTimeDesc(
            Long tenantId, Long employeeId);
    @Query(value = "select * from attendance_logs where employee_id = :employeeId "
            + "and check_out_time is null and manual_override = false and combined_area_id is null "
            + "order by check_in_time desc limit 1", nativeQuery = true)
    java.util.Optional<AttendanceLog> findFirstByEmployeeIdAndCheckOutTimeIsNullAndManualOverrideFalseOrderByCheckInTimeDesc(
            Long employeeId);
    List<AttendanceLog> findByTenantIdAndCheckOutTimeIsNullOrderByCheckInTimeDesc(Long tenantId);

    @Query("""
            select attendance from AttendanceLog attendance, Employee employee
            where attendance.tenantId = :tenantId
              and attendance.checkOutTime is null
              and employee.id = attendance.employeeId
              and employee.tenantId = :tenantId
              and (:branchId is null or employee.branchId = :branchId)
            order by attendance.checkInTime desc, attendance.id desc
            """)
    Page<AttendanceLog> findOpenSessions(@Param("tenantId") Long tenantId,
                                         @Param("branchId") Long branchId,
                                         Pageable pageable);

    java.util.Optional<AttendanceLog> findFirstByTenantIdAndEmployeeIdAndCheckOutTimeIsNotNullOrderByCheckOutTimeDesc(
            Long tenantId, Long employeeId);
    java.util.Optional<AttendanceLog> findFirstByEmployeeIdAndCheckOutTimeIsNotNullOrderByCheckOutTimeDesc(Long employeeId);

    // Ordered queries for recent logs
    List<AttendanceLog> findByTenantIdOrderByCheckInTimeDesc(Long tenantId, Pageable pageable);
    List<AttendanceLog> findAllByOrderByCheckInTimeDesc(Pageable pageable);

    // Legacy methods
    List<AttendanceLog> findByEmployeeIdAndCheckInTimeBetween(Long employeeId,
                                                               LocalDateTime start,
                                                               LocalDateTime end);
    boolean existsByTenantIdAndEmployeeIdAndCheckInTimeAndCheckOutTimeAndDoorId(Long tenantId,
                                                                                  Long employeeId,
                                                                                  LocalDateTime checkInTime,
                                                                                  LocalDateTime checkOutTime,
                                                                                  String doorId);
    boolean existsByEmployeeIdAndCheckInTimeAndCheckOutTimeAndDoorId(Long employeeId,
                                                                      LocalDateTime checkInTime,
                                                                      LocalDateTime checkOutTime,
                                                                      String doorId);
    List<AttendanceLog> findByDeviceId(String deviceId);
    List<AttendanceLog> findByCheckInTimeBetween(LocalDateTime start, LocalDateTime end);
    long countByCheckInTimeBetween(LocalDateTime start, LocalDateTime end);
}
