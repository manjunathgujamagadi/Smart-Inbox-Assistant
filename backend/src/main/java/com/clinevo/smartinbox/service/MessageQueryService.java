package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.*;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.dto.api.*;
import com.clinevo.smartinbox.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the read-side DTOs consumed by the Angular review screen: the queue
 * list and the per-message detail view (attachments, classifications, extracted
 * fields with source refs, tables, images, and the reviewer-relevant audit trail).
 */
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageRepository messageRepository;
    private final ClassificationRepository classificationRepository;
    private final ExtractedFieldRepository extractedFieldRepository;
    private final AttachmentRepository attachmentRepository;
    private final TableExtractedRepository tableExtractedRepository;
    private final ImageExtractedRepository imageExtractedRepository;
    private final LiteratureCaseRepository literatureCaseRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<MessageSummaryDto> listQueue() {
        return messageRepository.findAllByOrderByReceivedAtDesc().stream()
                .map(this::toSummary)
                .toList();
    }

    private MessageSummaryDto toSummary(Message m) {
        List<ClassificationDto> classifications = classificationRepository.findByMessageId(m.getId()).stream()
                .map(this::toClassificationDto)
                .toList();
        List<LiteratureCaseDto> literatureCases = literatureCaseRepository.findByMessageIdOrderByCaseNumberAsc(m.getId()).stream()
                .map(this::toLiteratureCaseDto)
                .toList();
        return new MessageSummaryDto(m.getId(), m.getSourceType().name(), m.getSender(), m.getSubject(),
                m.getReceivedAt(), m.getStatus().name(), classifications, literatureCases);
    }

    @Transactional(readOnly = true)
    public MessageDetailDto getDetail(Long messageId) {
        Message m = messageRepository.findById(messageId)
                .orElseThrow(() -> new EntityNotFoundException("Message not found: " + messageId));

        List<Attachment> attachments = attachmentRepository.findByMessageId(messageId);
        List<Classification> classifications = classificationRepository.findByMessageId(messageId);
        List<ExtractedField> fields = extractedFieldRepository.findByMessageId(messageId);

        List<TableDto> tables = attachments.stream()
                .flatMap(a -> tableExtractedRepository.findByAttachmentId(a.getId()).stream())
                .map(t -> new TableDto(t.getId(), t.getAttachment().getId(), t.getPageNumber(), t.getTableJson()))
                .toList();

        List<ImageDto> images = attachments.stream()
                .flatMap(a -> imageExtractedRepository.findByAttachmentId(a.getId()).stream())
                .map(i -> new ImageDto(i.getId(), i.getAttachment().getId(), i.getPageNumber(), i.getDescription(), i.isFlaggedForReview()))
                .toList();

        List<LiteratureCase> literatureCases = literatureCaseRepository.findByMessageIdOrderByCaseNumberAsc(messageId);

        List<AuditEntryDto> auditTrail = buildAuditTrail(messageId, attachments, classifications, fields, literatureCases);

        return new MessageDetailDto(
                m.getId(), m.getSourceType().name(), m.getSender(), m.getSubject(), m.getReceivedAt(),
                m.getBodyText(), m.getStatus().name(),
                attachments.stream().map(this::toAttachmentDto).toList(),
                classifications.stream().map(this::toClassificationDto).toList(),
                fields.stream().map(this::toFieldDto).toList(),
                tables, images,
                literatureCases.stream().map(this::toLiteratureCaseDto).toList(),
                auditTrail
        );
    }

    private List<AuditEntryDto> buildAuditTrail(Long messageId, List<Attachment> attachments,
                                                 List<Classification> classifications, List<ExtractedField> fields,
                                                 List<LiteratureCase> literatureCases) {
        Stream<AuditLog> messageEntries = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(EntityType.MESSAGE, messageId).stream();
        Stream<AuditLog> attachmentEntries = attachments.stream()
                .flatMap(a -> auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(EntityType.ATTACHMENT, a.getId()).stream());
        Stream<AuditLog> classificationEntries = classifications.stream()
                .flatMap(c -> auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(EntityType.CLASSIFICATION, c.getId()).stream());
        Stream<AuditLog> fieldEntries = fields.stream()
                .flatMap(f -> auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(EntityType.EXTRACTED_FIELD, f.getId()).stream());
        Stream<AuditLog> literatureCaseEntries = literatureCases.stream()
                .flatMap(lc -> auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(EntityType.LITERATURE_CASE, lc.getId()).stream());

        return Stream.of(messageEntries, attachmentEntries, classificationEntries, fieldEntries, literatureCaseEntries)
                .flatMap(s -> s)
                .sorted(Comparator.comparing(AuditLog::getCreatedAt))
                .map(a -> new AuditEntryDto(a.getId(), a.getEntityType().name(), a.getEntityId(), a.getActor(),
                        a.getAction(), a.getDetailJson(), a.getCreatedAt()))
                .toList();
    }

    private LiteratureCaseDto toLiteratureCaseDto(LiteratureCase lc) {
        return new LiteratureCaseDto(lc.getId(), lc.getAttachment().getId(), lc.getCaseNumber(), lc.isRelevantCase(),
                lc.getRelevanceReason(), lc.getConfidence(), lc.getSummary(), lc.getReviewerStatus().name(),
                lc.getReviewerIsRelevant(), lc.getReviewedBy(), lc.getReviewedAt());
    }

    private ClassificationDto toClassificationDto(Classification c) {
        return new ClassificationDto(c.getId(), c.getCategory().name(), c.getConfidence(), c.getReason(),
                c.getReviewerStatus().name(), c.getReviewerCategory() == null ? null : c.getReviewerCategory().name(),
                c.getReviewedBy(), c.getReviewedAt());
    }

    private ExtractedFieldDto toFieldDto(ExtractedField f) {
        return new ExtractedFieldDto(f.getId(), f.getAttachment() == null ? null : f.getAttachment().getId(),
                f.getCaseNumber(), f.getFieldGroup().name(), f.getFieldName(), f.getFieldValue(), f.getConfidence(),
                f.getSourceType().name(), f.getSourceRef(), f.getReviewerStatus().name(), f.getReviewerValue());
    }

    private AttachmentDto toAttachmentDto(Attachment a) {
        return new AttachmentDto(a.getId(), a.getFilename(), a.getMimeType(), a.isProcessable(),
                a.getPdfFlavor() == null ? null : a.getPdfFlavor().name(), a.getDetectedLanguage(),
                a.getPageCount(), a.isProcessed(), a.getSummary());
    }
}
