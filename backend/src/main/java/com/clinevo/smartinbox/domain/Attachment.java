package com.clinevo.smartinbox.domain;

import com.clinevo.smartinbox.domain.enums.PdfFlavor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "attachments")
@Getter
@Setter
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    private String filename;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "is_processable", nullable = false)
    private boolean processable;

    @Enumerated(EnumType.STRING)
    @Column(name = "pdf_flavor", length = 30)
    private PdfFlavor pdfFlavor;

    @Column(name = "detected_language")
    private String detectedLanguage;

    @Column(name = "storage_ref", nullable = false)
    private String storageRef;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(nullable = false)
    private boolean processed = false;

    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
