package com.clinevo.smartinbox.dto.api;

import java.time.Instant;

public record AuditEntryDto(
        Long id,
        String entityType,
        Long entityId,
        String actor,
        String action,
        String detailJson,
        Instant createdAt
) {}
