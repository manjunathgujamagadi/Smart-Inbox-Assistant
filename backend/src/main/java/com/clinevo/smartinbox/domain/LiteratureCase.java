package com.clinevo.smartinbox.domain;

import com.clinevo.smartinbox.domain.enums.ReviewerStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One real, identifiable patient case found within an independently-uploaded
 * article PDF (Section 4 bonus). An article can yield zero, one, or several
 * of these - case splitting is what distinguishes literature screening from
 * ordinary Section 3 ICSR extraction.
 */
@Entity
@Table(name = "literature_cases")
@Getter
@Setter
public class LiteratureCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id", nullable = false)
    private Attachment attachment;

    @Column(name = "case_number", nullable = false)
    private Integer caseNumber;

    @Column(name = "is_relevant_case", nullable = false)
    private boolean relevantCase;

    @Column(name = "relevance_reason", nullable = false)
    private String relevanceReason;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(nullable = false)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_status", nullable = false, length = 20)
    private ReviewerStatus reviewerStatus = ReviewerStatus.PENDING;

    @Column(name = "reviewer_is_relevant")
    private Boolean reviewerIsRelevant;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
