package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.AuditLog;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String SYSTEM_ACTOR = "system";

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void log(EntityType entityType, Long entityId, String actor, String action, Map<String, Object> detail) {
        AuditLog entry = new AuditLog();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setActor(actor);
        entry.setAction(action);
        if (detail != null) {
            try {
                entry.setDetailJson(objectMapper.writeValueAsString(detail));
            } catch (Exception e) {
                entry.setDetailJson("{\"error\":\"failed to serialize detail\"}");
            }
        }
        auditLogRepository.save(entry);
    }

    public void logSystem(EntityType entityType, Long entityId, String action, Map<String, Object> detail) {
        log(entityType, entityId, SYSTEM_ACTOR, action, detail);
    }
}
