package com.vita.auth.repository;

import com.vita.auth.entity.UserAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuditLogRepository extends JpaRepository<UserAuditLog, Long> {
    Page<UserAuditLog> findByTargetId(Long targetId, Pageable pageable);
}
