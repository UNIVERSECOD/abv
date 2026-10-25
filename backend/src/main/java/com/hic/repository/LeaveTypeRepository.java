package com.hic.repository;

import com.hic.model.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LeaveType t where t.tenantId = :tenantId and t.id = :id")
    Optional<LeaveType> lockForLeave(@Param("tenantId") Long tenantId, @Param("id") Long id);

    Optional<LeaveType> findByLeaveCode(String leaveCode);
    Optional<LeaveType> findByTenantIdAndLeaveCode(Long tenantId, String leaveCode);
    List<LeaveType> findByTenantId(Long tenantId);
}
