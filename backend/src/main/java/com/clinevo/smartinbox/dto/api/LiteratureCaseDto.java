package com.clinevo.smartinbox.dto.api;

import java.math.BigDecimal;
import java.time.Instant;

public record LiteratureCaseDto(
        Long id,
        Long attachmentId,
        int caseNumber,
        boolean relevantCase,
        String relevanceReason,
        BigDecimal confidence,
        String summary,
        String reviewerStatus,
        Boolean reviewerIsRelevant,
        String reviewedBy,
        Instant reviewedAt
) {}
