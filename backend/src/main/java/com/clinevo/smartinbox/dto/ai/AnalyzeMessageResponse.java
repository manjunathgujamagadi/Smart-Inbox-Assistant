package com.clinevo.smartinbox.dto.ai;

import java.util.List;

public record AnalyzeMessageResponse(
        List<ClassificationResult> classifications,
        List<ExtractedFieldResult> extractedFields
) {
    public record ClassificationResult(String category, double confidence, String reason) {}

    public record ExtractedFieldResult(
            String fieldGroup,
            String fieldName,
            String fieldValue,
            Double confidence,
            String sourceType,   // EMAIL_BODY | PDF_PAGE
            String sourceRef,
            Long attachmentId
    ) {}
}
