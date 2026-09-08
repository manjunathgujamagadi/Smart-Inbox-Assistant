package com.clinevo.smartinbox.dto.ai;

import java.util.List;

public record ScreenLiteratureResponse(
        List<CaseResult> cases
) {
    public record CaseResult(
            int caseNumber,
            boolean isRelevantCase,
            String relevanceReason,
            double confidence,
            String summary,
            List<AnalyzeMessageResponse.ExtractedFieldResult> extractedFields
    ) {}
}
