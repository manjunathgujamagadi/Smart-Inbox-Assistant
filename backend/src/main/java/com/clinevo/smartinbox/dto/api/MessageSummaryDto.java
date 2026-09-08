package com.clinevo.smartinbox.dto.api;

import java.time.Instant;
import java.util.List;

public record MessageSummaryDto(
        Long id,
        String sourceType,
        String sender,
        String subject,
        Instant receivedAt,
        String status,
        List<ClassificationDto> classifications,
        List<LiteratureCaseDto> literatureCases
) {}
