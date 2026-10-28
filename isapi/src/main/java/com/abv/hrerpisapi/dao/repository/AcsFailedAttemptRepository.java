package com.abv.hrerpisapi.dao.repository;

import com.abv.hrerpisapi.dao.entity.AcsFailedAttemptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcsFailedAttemptRepository extends JpaRepository<AcsFailedAttemptEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<AcsFailedAttemptEntity> {
    List<AcsFailedAttemptEntity> findByOrderByEventTimeDesc(Pageable pageable);

    List<AcsFailedAttemptEntity> findByDeviceIdOrderByEventTimeDesc(Long deviceId, Pageable pageable);
}
