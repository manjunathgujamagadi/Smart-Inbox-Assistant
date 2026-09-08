package com.clinevo.smartinbox.dto.api;

import java.time.Instant;
import java.util.List;

public record MessageDetailDto(
        Long id,
        String sourceType,
        String sender,
        String subject,
        Instant receivedAt,
        String bodyText,
        String status,
        List<AttachmentDto> attachments,
        List<ClassificationDto> classifications,
        List<ExtractedFieldDto> extractedFields,
        List<TableDto> tables,
        List<ImageDto> images,
        List<LiteratureCaseDto> literatureCases,
        List<AuditEntryDto> auditTrail
) {}
