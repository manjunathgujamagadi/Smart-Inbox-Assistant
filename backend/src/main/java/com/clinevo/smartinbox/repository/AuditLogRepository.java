package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.AuditLog;
import com.clinevo.smartinbox.domain.enums.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(EntityType entityType, Long entityId);
}
