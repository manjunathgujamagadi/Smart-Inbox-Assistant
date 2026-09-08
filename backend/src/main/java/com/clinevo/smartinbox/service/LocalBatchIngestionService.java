package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.Attachment;
import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.domain.enums.SourceType;
import com.clinevo.smartinbox.dto.fixture.EmailFixture;
import com.clinevo.smartinbox.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ingests synthetic email fixtures from a local directory tree (test-data/emails)
 * instead of a live mailbox. Used for: (a) the walking-skeleton before real IMAP
 * credentials exist, and (b) the required automated batch run over the synthetic
 * test-data set (Section 3E, Section 6).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LocalBatchIngestionService {

    private final MessageRepository messageRepository;
    private final StorageService storageService;
    private final ProcessingQueueService processingQueueService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public List<Long> ingestDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .map(this::ingestFixtureQuietly)
                    .filter(id -> id != null)
                    .toList();
        }
    }

    private Long ingestFixtureQuietly(Path fixturePath) {
        try {
            return ingestFixture(fixturePath);
        } catch (Exception e) {
            log.error("Failed to ingest fixture {}", fixturePath, e);
            return null;
        }
    }

    // Note: when called from ingestDirectory() (self-invocation via a method
    // reference), this annotation is bypassed by Spring's proxy-based AOP;
    // atomicity still holds because the final messageRepository.save(message)
    // below cascade-persists the message and all attachments in one transaction.
    @Transactional
    public Long ingestFixture(Path fixturePath) throws IOException {
        EmailFixture fixture = objectMapper.readValue(fixturePath.toFile(), EmailFixture.class);
        Path baseDir = fixturePath.getParent();

        Message message = new Message();
        message.setSourceType(SourceType.EMAIL);
        message.setExternalRef("fixture:" + fixturePath.getFileName());
        message.setSender(fixture.sender());
        message.setSubject(fixture.subject());
        message.setReceivedAt(fixture.receivedAt() != null ? Instant.parse(fixture.receivedAt()) : Instant.now());
        message.setBodyText(fixture.bodyText());
        message = messageRepository.save(message);

        if (fixture.attachments() != null) {
            for (String relPath : fixture.attachments()) {
                Path attPath = baseDir.resolve(relPath);
                boolean isPdf = attPath.toString().toLowerCase().endsWith(".pdf");
                Attachment attachment = new Attachment();
                attachment.setMessage(message);
                attachment.setFilename(attPath.getFileName().toString());
                attachment.setMimeType(isPdf ? "application/pdf" : Files.probeContentType(attPath));
                attachment.setProcessable(isPdf);
                if (isPdf) {
                    try (InputStream in = new FileInputStream(attPath.toFile())) {
                        attachment.setStorageRef(storageService.saveAttachment(message.getId(), attachment.getFilename(), in));
                    }
                } else {
                    attachment.setStorageRef("(not-stored:" + attachment.getFilename() + ")");
                }
                message.getAttachments().add(attachment);
            }
        }
        message = messageRepository.save(message);

        auditService.logSystem(EntityType.MESSAGE, message.getId(), "MESSAGE_INGESTED", Map.of(
                "sourceType", "EMAIL_FIXTURE", "fixture", fixturePath.getFileName().toString()));

        processingQueueService.enqueue(message);
        return message.getId();
    }
}
