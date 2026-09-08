package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.Classification;
import com.clinevo.smartinbox.domain.ExtractedField;
import com.clinevo.smartinbox.domain.LiteratureCase;
import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.enums.Category;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.domain.enums.ReviewerStatus;
import com.clinevo.smartinbox.dto.api.ReviewRequest;
import com.clinevo.smartinbox.repository.ClassificationRepository;
import com.clinevo.smartinbox.repository.ExtractedFieldRepository;
import com.clinevo.smartinbox.repository.LiteratureCaseRepository;
import com.clinevo.smartinbox.repository.MessageRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Applies a human reviewer's accept/override decisions and writes a timestamped
 * audit entry for every action, per Section 3C / Section 3E ("every reviewer
 * action should be timestamped").
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final MessageRepository messageRepository;
    private final ClassificationRepository classificationRepository;
    private final ExtractedFieldRepository extractedFieldRepository;
    private final LiteratureCaseRepository literatureCaseRepository;
    private final AuditService auditService;

    @Transactional
    public void applyReview(Long messageId, ReviewRequest request) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new EntityNotFoundException("Message not found: " + messageId));

        String reviewer = (request.reviewerName() == null || request.reviewerName().isBlank())
                ? "demo-reviewer" : request.reviewerName();

        if (request.classifications() != null) {
            for (ReviewRequest.ClassificationReview cr : request.classifications()) {
                Classification c = classificationRepository.findById(cr.classificationId())
                        .orElseThrow(() -> new EntityNotFoundException("Classification not found: " + cr.classificationId()));
                if (!c.getMessage().getId().equals(messageId)) {
                    throw new IllegalArgumentException("Classification does not belong to message " + messageId);
                }
                c.setReviewerStatus(ReviewerStatus.valueOf(cr.decision()));
                if (cr.overrideCategory() != null) {
                    c.setReviewerCategory(Category.valueOf(cr.overrideCategory()));
                }
                c.setReviewedBy(reviewer);
                c.setReviewedAt(Instant.now());
                classificationRepository.save(c);

                auditService.log(EntityType.CLASSIFICATION, c.getId(), reviewer, "REVIEWER_" + cr.decision(), Map.of(
                        "overrideCategory", cr.overrideCategory() == null ? "" : cr.overrideCategory()));
            }
        }

        if (request.fields() != null) {
            for (ReviewRequest.FieldReview fr : request.fields()) {
                ExtractedField f = extractedFieldRepository.findById(fr.fieldId())
                        .orElseThrow(() -> new EntityNotFoundException("Field not found: " + fr.fieldId()));
                if (!f.getMessage().getId().equals(messageId)) {
                    throw new IllegalArgumentException("Field does not belong to message " + messageId);
                }
                f.setReviewerStatus(ReviewerStatus.valueOf(fr.decision()));
                if (fr.overrideValue() != null) {
                    f.setReviewerValue(fr.overrideValue());
                }
                extractedFieldRepository.save(f);

                auditService.log(EntityType.EXTRACTED_FIELD, f.getId(), reviewer, "REVIEWER_" + fr.decision(), Map.of(
                        "overrideValue", fr.overrideValue() == null ? "" : fr.overrideValue()));
            }
        }

        if (request.literatureCases() != null) {
            for (ReviewRequest.LiteratureCaseReview lr : request.literatureCases()) {
                LiteratureCase lc = literatureCaseRepository.findById(lr.caseId())
                        .orElseThrow(() -> new EntityNotFoundException("Literature case not found: " + lr.caseId()));
                if (!lc.getMessage().getId().equals(messageId)) {
                    throw new IllegalArgumentException("Literature case does not belong to message " + messageId);
                }
                lc.setReviewerStatus(ReviewerStatus.valueOf(lr.decision()));
                if (lr.overrideIsRelevant() != null) {
                    lc.setReviewerIsRelevant(lr.overrideIsRelevant());
                }
                lc.setReviewedBy(reviewer);
                lc.setReviewedAt(Instant.now());
                literatureCaseRepository.save(lc);

                auditService.log(EntityType.LITERATURE_CASE, lc.getId(), reviewer, "REVIEWER_" + lr.decision(), Map.of(
                        "overrideIsRelevant", lr.overrideIsRelevant() == null ? "" : lr.overrideIsRelevant()));
            }
        }
    }
}
