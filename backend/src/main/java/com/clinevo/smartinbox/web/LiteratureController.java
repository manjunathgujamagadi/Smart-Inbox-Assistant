package com.clinevo.smartinbox.web;

import com.clinevo.smartinbox.domain.Attachment;
import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.domain.enums.SourceType;
import com.clinevo.smartinbox.repository.MessageRepository;
import com.clinevo.smartinbox.service.AuditService;
import com.clinevo.smartinbox.service.ProcessingQueueService;
import com.clinevo.smartinbox.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Section 4 bonus: literature screening. Accepts a batch of article PDFs
 * uploaded independently of the mailbox - each becomes its own Message
 * (source_type=UPLOAD) with one attachment, reusing the same storage/queue/
 * audit pipeline as Section 3, but routed to case-splitting screening instead
 * of ICSR/PQC/MI classification (see DocumentProcessingService.processLiterature).
 */
@RestController
@RequestMapping("/api/literature")
@RequiredArgsConstructor
@CrossOrigin
public class LiteratureController {

    private final MessageRepository messageRepository;
    private final StorageService storageService;
    private final ProcessingQueueService processingQueueService;
    private final AuditService auditService;

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("files") List<MultipartFile> files) throws IOException {
        List<Long> messageIds = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "article.pdf";

            Message message = new Message();
            message.setSourceType(SourceType.UPLOAD);
            message.setSender("literature-upload");
            message.setSubject(filename);
            message.setReceivedAt(Instant.now());
            message.setBodyText(null);
            message = messageRepository.save(message);

            Attachment attachment = new Attachment();
            attachment.setMessage(message);
            attachment.setFilename(filename);
            attachment.setMimeType("application/pdf");
            attachment.setProcessable(true);
            attachment.setStorageRef(storageService.saveAttachment(message.getId(), filename, file.getInputStream()));
            message.getAttachments().add(attachment);
            message = messageRepository.save(message);

            auditService.logSystem(EntityType.MESSAGE, message.getId(), "MESSAGE_INGESTED", Map.of(
                    "sourceType", "LITERATURE_UPLOAD", "filename", filename));

            processingQueueService.enqueue(message);
            messageIds.add(message.getId());
        }
        return Map.of("uploaded", messageIds.size(), "messageIds", messageIds);
    }
}
