package com.clinevo.smartinbox.domain;

import com.clinevo.smartinbox.domain.enums.FieldGroup;
import com.clinevo.smartinbox.domain.enums.ReviewerStatus;
import com.clinevo.smartinbox.domain.enums.SourceRefType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "extracted_fields")
@Getter
@Setter
public class ExtractedField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id")
    private Attachment attachment;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_group", nullable = false, length = 30)
    private FieldGroup fieldGroup;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Column(name = "field_value", nullable = false)
    private String fieldValue;

    /** Set only for literature-screening fields (Section 4 bonus): which case within the article this belongs to. */
    @Column(name = "case_number")
    private Integer caseNumber;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceRefType sourceType;

    @Column(name = "source_ref")
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_status", nullable = false, length = 20)
    private ReviewerStatus reviewerStatus = ReviewerStatus.PENDING;

    @Column(name = "reviewer_value")
    private String reviewerValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
