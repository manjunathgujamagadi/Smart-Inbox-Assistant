package com.clinevo.smartinbox.dto.api;

import java.util.List;

public record ReviewRequest(
        String reviewerName,
        List<ClassificationReview> classifications,
        List<FieldReview> fields,
        List<LiteratureCaseReview> literatureCases
) {
    public record ClassificationReview(Long classificationId, String decision, String overrideCategory) {}
    // decision: ACCEPTED | OVERRIDDEN | REJECTED

    public record FieldReview(Long fieldId, String decision, String overrideValue) {}
    // decision: ACCEPTED | OVERRIDDEN

    public record LiteratureCaseReview(Long caseId, String decision, Boolean overrideIsRelevant) {}
    // decision: ACCEPTED | OVERRIDDEN | REJECTED
}
