package com.clinevo.smartinbox.dto.api;

import java.math.BigDecimal;

public record ExtractedFieldDto(
        Long id,
        Long attachmentId,
        Integer caseNumber,
        String fieldGroup,
        String fieldName,
        String fieldValue,
        BigDecimal confidence,
        String sourceType,
        String sourceRef,
        String reviewerStatus,
        String reviewerValue
) {}
