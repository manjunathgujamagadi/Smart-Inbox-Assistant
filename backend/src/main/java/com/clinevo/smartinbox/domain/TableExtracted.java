package com.clinevo.smartinbox.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "tables_extracted")
@Getter
@Setter
public class TableExtracted {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id", nullable = false)
    private Attachment attachment;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    /** Structured table: { "headers": [...], "rows": [[...], ...] } */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "table_json", nullable = false, columnDefinition = "jsonb")
    private String tableJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
