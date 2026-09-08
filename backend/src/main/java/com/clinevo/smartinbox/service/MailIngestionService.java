package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.config.AppProperties;
import com.clinevo.smartinbox.domain.Attachment;
import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.domain.enums.SourceType;
import com.clinevo.smartinbox.repository.MessageRepository;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

/**
 * Real IMAP intake (Section 3A): polls a shared test mailbox, extracts
 * sender/subject/date/body, pulls PDF attachments for processing, logs
 * (but does not process) any other attachment type, then hands each message
 * to the processing queue.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MailIngestionService {

    private final AppProperties props;
    private final MessageRepository messageRepository;
    private final StorageService storageService;
    private final ProcessingQueueService processingQueueService;
    private final AuditService auditService;

    @Scheduled(fixedDelayString = "${app.mail.poll-interval-ms}")
    public void pollInbox() {
        if (!props.mail().enabled()) {
            return;
        }
        try {
            fetchAndIngestNewMessages();
        } catch (Exception e) {
            log.error("IMAP poll failed", e);
        }
    }

    public int fetchAndIngestNewMessages() throws MessagingException, IOException {
        Properties mailProps = new Properties();
        mailProps.put("mail.store.protocol", "imaps");
        mailProps.put("mail.imaps.ssl.enable", "true");
        mailProps.put("mail.imaps.connectiontimeout", "10000");
        mailProps.put("mail.imaps.timeout", "10000");

        Session session = Session.getInstance(mailProps);
        String host = System.getenv().getOrDefault("MAIL_IMAP_HOST", "imap.gmail.com");
        int port = Integer.parseInt(System.getenv().getOrDefault("MAIL_IMAP_PORT", "993"));
        String username = System.getenv("MAIL_USERNAME");
        String password = System.getenv("MAIL_PASSWORD");

        int ingested = 0;
        try (Store store = session.getStore("imaps")) {
            store.connect(host, port, username, password);
            Folder inbox = store.getFolder(props.mail().inboxFolder());
            inbox.open(Folder.READ_ONLY);
            try {
                for (jakarta.mail.Message m : inbox.getMessages()) {
                    if (ingestIfNew(m)) {
                        ingested++;
                    }
                }
            } finally {
                inbox.close(false);
            }
        }
        return ingested;
    }

    // Not @Transactional: this is invoked via self-reference (this.ingestIfNew(...)),
    // which bypasses Spring's proxy-based AOP. Durability instead relies on the final
    // messageRepository.save(message) below, which cascade-persists the message and
    // every attachment added to its collection in one Spring Data JPA transaction.
    private boolean ingestIfNew(jakarta.mail.Message m) throws MessagingException, IOException {
        String externalRef = extractMessageId(m);
        if (externalRef != null && messageRepository.findByExternalRef(externalRef).isPresent()) {
            return false; // already ingested
        }

        Message message = new Message();
        message.setSourceType(SourceType.EMAIL);
        message.setExternalRef(externalRef);
        message.setSender(extractSender(m));
        message.setSubject(m.getSubject());
        message.setReceivedAt(m.getSentDate() != null ? m.getSentDate().toInstant() : Instant.now());
        StringBuilder body = new StringBuilder();
        message = messageRepository.save(message);

        extractContent(m, message, body);
        message.setBodyText(body.toString().trim());
        message = messageRepository.save(message);

        auditService.logSystem(EntityType.MESSAGE, message.getId(), "MESSAGE_INGESTED", Map.of(
                "sourceType", "EMAIL", "sender", String.valueOf(message.getSender())));

        processingQueueService.enqueue(message);
        return true;
    }

    private String extractMessageId(jakarta.mail.Message m) throws MessagingException {
        String[] ids = m.getHeader("Message-ID");
        return (ids != null && ids.length > 0) ? ids[0] : null;
    }

    private String extractSender(jakarta.mail.Message m) throws MessagingException {
        Address[] from = m.getFrom();
        if (from == null || from.length == 0) {
            return "unknown";
        }
        Address a = from[0];
        return (a instanceof InternetAddress ia) ? ia.getAddress() : a.toString();
    }

    private void extractContent(Part part, Message message, StringBuilder body) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof String s) {
            if (part.isMimeType("text/plain") || body.isEmpty()) {
                body.append(s).append("\n");
            }
        } else if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disposition = bp.getDisposition();
                String filename = bp.getFileName();

                boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || filename != null;
                if (isAttachment) {
                    handleAttachment(bp, message, filename);
                } else {
                    extractContent(bp, message, body);
                }
            }
        }
    }

    private void handleAttachment(BodyPart bp, Message message, String filename) throws MessagingException, IOException {
        String decodedName = filename != null ? MimeUtility.decodeText(filename) : "attachment.bin";
        String mimeType = bp.getContentType() != null ? bp.getContentType().split(";")[0].trim() : "application/octet-stream";
        boolean isPdf = decodedName.toLowerCase().endsWith(".pdf") || "application/pdf".equalsIgnoreCase(mimeType);

        Attachment attachment = new Attachment();
        attachment.setMessage(message);
        attachment.setFilename(decodedName);
        attachment.setMimeType(mimeType);
        attachment.setProcessable(isPdf);

        if (isPdf) {
            try (ByteArrayInputStream in = new ByteArrayInputStream(bp.getInputStream().readAllBytes())) {
                String path = storageService.saveAttachment(message.getId(), decodedName, in);
                attachment.setStorageRef(path);
            }
        } else {
            // Non-PDF: logged only, not stored/processed, per spec.
            attachment.setStorageRef("(not-stored:" + decodedName + ")");
        }

        message.getAttachments().add(attachment);
        auditService.logSystem(EntityType.MESSAGE, message.getId(), isPdf ? "ATTACHMENT_QUEUED" : "ATTACHMENT_LOGGED_ONLY",
                Map.of("filename", decodedName, "mimeType", mimeType));
    }
}
