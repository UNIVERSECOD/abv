package com.abv.hrerpisapi.dao.repository;

import com.abv.hrerpisapi.dao.entity.AttendancePunchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;

public interface AttendancePunchRepository extends JpaRepository<AttendancePunchEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<AttendancePunchEntity> {
    List<AttendancePunchEntity> findByOrderByPunchTimeDesc(Pageable pageable);

    List<AttendancePunchEntity> findByDeviceIdOrderByPunchTimeDesc(Long deviceId, Pageable pageable);

    List<AttendancePunchEntity> findByEmployeeNoOrderByPunchTimeDesc(String employeeNo, Pageable pageable);

    List<AttendancePunchEntity> findByDeviceIdAndEmployeeNoOrderByPunchTimeDesc(Long deviceId, String employeeNo, Pageable pageable);

    List<AttendancePunchEntity> findByPunchTimeBetweenOrderByPunchTimeAsc(OffsetDateTime start, OffsetDateTime end, Pageable pageable);

    List<AttendancePunchEntity> findByDeviceIdAndPunchTimeBetweenOrderByPunchTimeAsc(Long deviceId, OffsetDateTime start, OffsetDateTime end, Pageable pageable);

    List<AttendancePunchEntity> findByEmployeeNoAndPunchTimeBetweenOrderByPunchTimeAsc(String employeeNo, OffsetDateTime start, OffsetDateTime end, Pageable pageable);

    List<AttendancePunchEntity> findByDeviceIdAndEmployeeNoAndPunchTimeBetweenOrderByPunchTimeAsc(Long deviceId, String employeeNo, OffsetDateTime start, OffsetDateTime end, Pageable pageable);
}
