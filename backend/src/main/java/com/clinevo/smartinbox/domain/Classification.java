package com.clinevo.smartinbox.domain;

import com.clinevo.smartinbox.domain.enums.Category;
import com.clinevo.smartinbox.domain.enums.ReviewerStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "classifications")
@Getter
@Setter
public class Classification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    private String reason;

    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_status", nullable = false, length = 20)
    private ReviewerStatus reviewerStatus = ReviewerStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_category", length = 20)
    private Category reviewerCategory;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
