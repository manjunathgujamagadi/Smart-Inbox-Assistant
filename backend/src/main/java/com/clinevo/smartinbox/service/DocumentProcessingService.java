package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.*;
import com.clinevo.smartinbox.domain.enums.*;
import com.clinevo.smartinbox.dto.ai.AnalyzeMessageRequest;
import com.clinevo.smartinbox.dto.ai.AnalyzeMessageResponse;
import com.clinevo.smartinbox.dto.ai.PdfProcessResponse;
import com.clinevo.smartinbox.dto.ai.ScreenLiteratureRequest;
import com.clinevo.smartinbox.dto.ai.ScreenLiteratureResponse;
import com.clinevo.smartinbox.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the AI pipeline for a single message: process each PDF attachment
 * (flavor detection, extraction, tables, images, summary), then either classify
 * the message (Section 3 - email/batch intake) or screen it as literature
 * (Section 4 bonus - independently uploaded articles), all via the Python AI
 * service. Every AI decision is written to the audit log.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final AiServiceClient aiServiceClient;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final ClassificationRepository classificationRepository;
    private final ExtractedFieldRepository extractedFieldRepository;
    private final TableExtractedRepository tableExtractedRepository;
    private final ImageExtractedRepository imageExtractedRepository;
    private final LiteratureCaseRepository literatureCaseRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Takes a message ID rather than an entity and re-fetches it here, inside
     * this method's own transaction/session: the caller (ProcessingQueueService,
     * running asynchronously with no ambient transaction) only has a detached
     * reference, and Message.attachments is a lazy collection that can't be
     * initialized outside the session that loaded it.
     */
    @Transactional
    public void process(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new EntityNotFoundException("Message not found: " + messageId));

        boolean isLiterature = message.getSourceType() == SourceType.UPLOAD;
        List<AnalyzeMessageRequest.AttachmentContext> attachmentContexts = new ArrayList<>();

        for (Attachment attachment : message.getAttachments()) {
            if (!attachment.isProcessable()) {
                continue; // non-PDF: logged only, per spec
            }
            PdfProcessResponse result = aiServiceClient.processPdf(attachment.getId(), attachment.getStorageRef());
            applyPdfResult(attachment, result);
            List<AnalyzeMessageRequest.PageContext> pageContexts = result.pages() == null ? List.of() :
                    result.pages().stream()
                            .map(p -> new AnalyzeMessageRequest.PageContext(
                                    p.pageNumber(), p.translatedText() != null ? p.translatedText() : p.text()))
                            .toList();

            if (isLiterature) {
                // Section 4 bonus: each uploaded article is screened for real patient
                // cases independently (an article may yield zero, one, or several).
                processLiterature(message, attachment, pageContexts);
            } else {
                attachmentContexts.add(new AnalyzeMessageRequest.AttachmentContext(
                        attachment.getId(), attachment.getFilename(), result.summary(), result.flavor(), pageContexts));
            }
        }

        if (!isLiterature) {
            AnalyzeMessageRequest request = new AnalyzeMessageRequest(
                    new AnalyzeMessageRequest.MessageContext(
                            message.getSender(),
                            message.getSubject(),
                            message.getBodyText(),
                            message.getReceivedAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(message.getReceivedAt())),
                    attachmentContexts
            );
            AnalyzeMessageResponse analysis = aiServiceClient.analyzeMessage(request);
            applyAnalysis(message, analysis);
        }
    }

    private void applyPdfResult(Attachment attachment, PdfProcessResponse result) {
        attachment.setPdfFlavor(PdfFlavor.valueOf(result.flavor()));
        attachment.setDetectedLanguage(result.detectedLanguage());
        attachment.setPageCount(result.pageCount());
        attachment.setSummary(result.summary());
        attachment.setProcessed(true);
        attachmentRepository.save(attachment);

        if (result.tables() != null) {
            for (PdfProcessResponse.TableDto t : result.tables()) {
                TableExtracted te = new TableExtracted();
                te.setAttachment(attachment);
                te.setPageNumber(t.pageNumber());
                te.setTableJson(toJson(Map.of("headers", t.headers(), "rows", t.rows())));
                tableExtractedRepository.save(te);
            }
        }
        if (result.images() != null) {
            for (PdfProcessResponse.ImageDto img : result.images()) {
                ImageExtracted ie = new ImageExtracted();
                ie.setAttachment(attachment);
                ie.setPageNumber(img.pageNumber());
                ie.setDescription(img.description());
                ie.setFlaggedForReview(img.flaggedForReview());
                imageExtractedRepository.save(ie);
            }
        }

        auditService.logSystem(EntityType.ATTACHMENT, attachment.getId(), "AI_PDF_PROCESSED", Map.of(
                "flavor", result.flavor(),
                "pageCount", result.pageCount() == null ? -1 : result.pageCount(),
                "confidence", result.confidence() == null ? -1 : result.confidence()
        ));
    }

    private void applyAnalysis(Message message, AnalyzeMessageResponse analysis) {
        for (AnalyzeMessageResponse.ClassificationResult c : analysis.classifications()) {
            Classification classification = new Classification();
            classification.setMessage(message);
            classification.setCategory(Category.valueOf(c.category()));
            classification.setConfidence(BigDecimal.valueOf(c.confidence()));
            classification.setReason(c.reason());
            classification.setModel("azure-openai:gpt-4o-mini");
            classificationRepository.save(classification);

            auditService.logSystem(EntityType.CLASSIFICATION, classification.getId(), "AI_CLASSIFIED", Map.of(
                    "category", c.category(), "confidence", c.confidence(), "reason", c.reason()
            ));
        }

        for (AnalyzeMessageResponse.ExtractedFieldResult f : analysis.extractedFields()) {
            ExtractedField field = new ExtractedField();
            field.setMessage(message);
            if (f.attachmentId() != null) {
                attachmentRepository.findById(f.attachmentId()).ifPresent(field::setAttachment);
            }
            field.setFieldGroup(FieldGroup.valueOf(f.fieldGroup()));
            field.setFieldName(f.fieldName());
            field.setFieldValue(f.fieldValue());
            field.setConfidence(f.confidence() == null ? null : BigDecimal.valueOf(f.confidence()));
            field.setSourceType(SourceRefType.valueOf(f.sourceType()));
            field.setSourceRef(f.sourceRef());
            extractedFieldRepository.save(field);

            auditService.logSystem(EntityType.EXTRACTED_FIELD, field.getId(), "AI_EXTRACTED", Map.of(
                    "fieldGroup", f.fieldGroup(), "fieldName", f.fieldName(),
                    "sourceType", f.sourceType(), "sourceRef", f.sourceRef() == null ? "" : f.sourceRef()
            ));
        }
    }

    private void processLiterature(Message message, Attachment attachment, List<AnalyzeMessageRequest.PageContext> pages) {
        ScreenLiteratureResponse response = aiServiceClient.screenLiterature(
                new ScreenLiteratureRequest(attachment.getId(), attachment.getFilename(), pages));

        for (ScreenLiteratureResponse.CaseResult c : response.cases()) {
            LiteratureCase lc = new LiteratureCase();
            lc.setMessage(message);
            lc.setAttachment(attachment);
            lc.setCaseNumber(c.caseNumber());
            lc.setRelevantCase(c.isRelevantCase());
            lc.setRelevanceReason(c.relevanceReason());
            lc.setConfidence(BigDecimal.valueOf(c.confidence()));
            lc.setSummary(c.summary());
            lc = literatureCaseRepository.save(lc);

            auditService.logSystem(EntityType.LITERATURE_CASE, lc.getId(), "AI_SCREENED", Map.of(
                    "caseNumber", c.caseNumber(), "isRelevantCase", c.isRelevantCase(), "confidence", c.confidence()));

            if (c.extractedFields() != null) {
                for (AnalyzeMessageResponse.ExtractedFieldResult f : c.extractedFields()) {
                    ExtractedField field = new ExtractedField();
                    field.setMessage(message);
                    field.setAttachment(attachment);
                    field.setCaseNumber(c.caseNumber());
                    field.setFieldGroup(FieldGroup.valueOf(f.fieldGroup()));
                    field.setFieldName(f.fieldName());
                    field.setFieldValue(f.fieldValue());
                    field.setConfidence(f.confidence() == null ? null : BigDecimal.valueOf(f.confidence()));
                    field.setSourceType(SourceRefType.valueOf(f.sourceType()));
                    field.setSourceRef(f.sourceRef());
                    extractedFieldRepository.save(field);

                    auditService.logSystem(EntityType.EXTRACTED_FIELD, field.getId(), "AI_EXTRACTED", Map.of(
                            "fieldGroup", f.fieldGroup(), "fieldName", f.fieldName(), "caseNumber", c.caseNumber()
                    ));
                }
            }
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("Failed to serialize table JSON", e);
            return "{}";
        }
    }
}
