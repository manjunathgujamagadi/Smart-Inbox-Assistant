package com.clinevo.smartinbox.dto.api;

import java.math.BigDecimal;
import java.time.Instant;

public record ClassificationDto(
        Long id,
        String category,
        BigDecimal confidence,
        String reason,
        String reviewerStatus,
        String reviewerCategory,
        String reviewedBy,
        Instant reviewedAt
) {}
