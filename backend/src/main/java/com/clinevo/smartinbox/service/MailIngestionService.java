package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.config.AppProperties;
import com.clinevo.smartinbox.domain.Attachment;
import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.domain.enums.SourceType;
import com.clinevo.smartinbox.dto.api.MailStatusDto;
import com.clinevo.smartinbox.dto.api.MailStatusDto.MailFetchResultDto;
import com.clinevo.smartinbox.repository.MessageRepository;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Real IMAP intake (Section 3A): polls a shared test mailbox, extracts
 * sender/subject/date/body, pulls PDF attachments for processing, logs
 * (but does not process) any other attachment type, then hands each message
 * to the processing queue.
 *
 * Design notes:
 * - Only mail received on/after a lower bound is scanned (the last ingested
 *   IMAP message minus a day, or MAIL_INGEST_SINCE / the day before first
 *   startup), so connecting an existing mailbox doesn't ingest its whole history.
 *   The mailbox is opened read-only; de-duplication is by Message-ID.
 * - Each email is fully parsed in memory before anything is written, then
 *   persisted + enqueued in one transaction. A failure leaves no row behind,
 *   so the email is simply retried on the next poll instead of getting stuck.
 * - Forwarded emails (message/rfc822 parts) are unwrapped: their text is kept
 *   and their PDFs are processed like direct attachments.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MailIngestionService {

    static final String EXTERNAL_REF_PREFIX = "imap:";
    private static final int MAX_EXTERNAL_REF = 255;
    private static final int MAX_SUBJECT = 1000;
    private static final int MAX_SENDER = 255;

    private final AppProperties props;
    private final MessageRepository messageRepository;
    private final StorageService storageService;
    private final ProcessingQueueService processingQueueService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    private final ReentrantLock pollLock = new ReentrantLock();
    private final Instant startedAt = Instant.now();
    private volatile Instant lastPollAt;
    private volatile MailFetchResultDto lastResult;
    private volatile String lastError;
    private volatile boolean warnedUnconfigured;

    @Scheduled(fixedDelayString = "${app.mail.poll-interval-ms}", initialDelay = 10_000)
    public void pollInbox() {
        if (!props.mail().enabled()) {
            return;
        }
        if (!isConfigured()) {
            if (!warnedUnconfigured) {
                log.warn("MAIL_POLLING_ENABLED=true but MAIL_USERNAME / MAIL_PASSWORD are not set - skipping IMAP polling");
                warnedUnconfigured = true;
            }
            return;
        }
        try {
            fetchAndIngestNewMessages();
        } catch (MailboxBusyException e) {
            log.debug("Skipping scheduled poll: {}", e.getMessage());
        } catch (Exception e) {
            log.error("IMAP poll failed", e);
        }
    }

    public MailStatusDto status() {
        AppProperties.Mail cfg = props.mail();
        return new MailStatusDto(cfg.enabled(), isConfigured(), cfg.username(), cfg.inboxFolder(),
                cfg.pollIntervalMs(), lastPollAt, lastResult, lastError);
    }

    /** Connects to the mailbox and ingests every new email. Used by the scheduler and the "Check mailbox" button. */
    public MailFetchResultDto fetchAndIngestNewMessages() throws MessagingException {
        if (!isConfigured()) {
            throw new IllegalArgumentException("Mailbox not configured: set MAIL_USERNAME and MAIL_PASSWORD (see README)");
        }
        if (!pollLock.tryLock()) {
            throw new MailboxBusyException("A mailbox check is already running");
        }
        try {
            MailFetchResultDto result = doFetch();
            lastResult = result;
            lastError = null;
            if (result.ingested() > 0 || result.failed() > 0) {
                log.info("IMAP poll: {} new, {} already ingested, {} failed (scanned {} since {})",
                        result.ingested(), result.alreadyIngested(), result.failed(), result.scanned(), result.since());
            }
            return result;
        } catch (MessagingException | RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            lastPollAt = Instant.now();
            pollLock.unlock();
        }
    }

    private MailFetchResultDto doFetch() throws MessagingException {
        AppProperties.Mail cfg = props.mail();
        Properties mailProps = new Properties();
        mailProps.put("mail.store.protocol", "imaps");
        mailProps.put("mail.imaps.ssl.enable", "true");
        mailProps.put("mail.imaps.connectiontimeout", "10000");
        mailProps.put("mail.imaps.timeout", "30000");
        mailProps.put("mail.mime.decodefilename", "true");
        if (StringUtils.hasText(cfg.sslTrust())) {
            mailProps.put("mail.imaps.ssl.trust", cfg.sslTrust());
            mailProps.put("mail.imaps.ssl.checkserveridentity", "false");
        }

        Instant since = searchSince();
        int scanned = 0, ingested = 0, already = 0, failed = 0;

        Session session = Session.getInstance(mailProps);
        try (Store store = session.getStore("imaps")) {
            store.connect(cfg.host(), cfg.port(), cfg.username(), cfg.password());
            Folder inbox = store.getFolder(cfg.inboxFolder());
            inbox.open(Folder.READ_ONLY);
            try {
                // IMAP SINCE is day-granular, so this may return a little extra; Message-ID dedup handles it.
                jakarta.mail.Message[] candidates = inbox.search(
                        new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(UIDFolder.FetchProfileItem.UID);
                fp.add("Message-ID");
                inbox.fetch(candidates, fp);

                for (jakarta.mail.Message m : candidates) {
                    scanned++;
                    try {
                        String externalRef = externalRef(inbox, m);
                        if (messageRepository.findByExternalRef(externalRef).isPresent()) {
                            already++;
                            continue;
                        }
                        ParsedEmail parsed = parse(m);
                        persistAndEnqueue(externalRef, parsed);
                        ingested++;
                    } catch (Exception e) {
                        // Nothing was persisted for this email, so the next poll retries it.
                        failed++;
                        log.error("Failed to ingest email '{}'", safeSubject(m), e);
                    }
                }
            } finally {
                inbox.close(false);
            }
        }
        return new MailFetchResultDto(since, scanned, ingested, already, failed);
    }

    /**
     * Lower bound for the IMAP search: a day before the newest email already ingested
     * (so downtime never loses mail), else MAIL_INGEST_SINCE, else the day before first startup.
     */
    private Instant searchSince() {
        Instant floor = StringUtils.hasText(props.mail().ingestSince())
                ? LocalDate.parse(props.mail().ingestSince().trim()).atStartOfDay(ZoneOffset.UTC).toInstant()
                : null;
        Optional<Instant> newest = messageRepository
                .findTopByExternalRefStartingWithOrderByReceivedAtDesc(EXTERNAL_REF_PREFIX)
                .map(Message::getReceivedAt)
                .filter(Objects::nonNull)
                .map(t -> t.minus(1, ChronoUnit.DAYS));
        if (newest.isPresent()) {
            return floor != null && floor.isAfter(newest.get()) ? floor : newest.get();
        }
        return floor != null ? floor : startedAt.minus(1, ChronoUnit.DAYS);
    }

    private Long persistAndEnqueue(String externalRef, ParsedEmail parsed) {
        return transactionTemplate.execute(tx -> {
            Message message = new Message();
            message.setSourceType(SourceType.EMAIL);
            message.setExternalRef(externalRef);
            message.setSender(truncate(parsed.sender, MAX_SENDER));
            message.setSubject(truncate(parsed.subject, MAX_SUBJECT));
            message.setReceivedAt(parsed.date);
            message.setBodyText(parsed.body.toString().trim());
            message = messageRepository.save(message);

            for (ParsedAttachment pa : parsed.attachments) {
                Attachment attachment = new Attachment();
                attachment.setMessage(message);
                attachment.setFilename(pa.filename());
                attachment.setMimeType(pa.mimeType());
                attachment.setProcessable(pa.pdf());
                attachment.setStorageRef(pa.pdf()
                        ? storageService.saveAttachment(message.getId(), pa.filename(), new ByteArrayInputStream(pa.data()))
                        : "(not-stored:" + pa.filename() + ")"); // non-PDF: logged only, per spec
                message.getAttachments().add(attachment);
            }
            message = messageRepository.save(message);

            auditService.logSystem(EntityType.MESSAGE, message.getId(), "MESSAGE_INGESTED", Map.of(
                    "sourceType", "EMAIL_IMAP",
                    "mailbox", props.mail().username() + "/" + props.mail().inboxFolder(),
                    "messageId", externalRef,
                    "sender", String.valueOf(message.getSender()),
                    "attachments", parsed.attachments.size()));
            for (ParsedAttachment pa : parsed.attachments) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("filename", pa.filename());
                detail.put("mimeType", pa.mimeType());
                detail.put("sizeBytes", pa.data().length);
                if (pa.forwarded()) {
                    detail.put("fromForwardedMessage", true);
                }
                auditService.logSystem(EntityType.MESSAGE, message.getId(),
                        pa.pdf() ? "ATTACHMENT_QUEUED" : "ATTACHMENT_LOGGED_ONLY", detail);
            }

            processingQueueService.enqueue(message);
            return message.getId();
        });
    }

    // ---------------------------------------------------------------- parsing

    private record ParsedAttachment(String filename, String mimeType, byte[] data, boolean pdf, boolean forwarded) {}

    private static final class ParsedEmail {
        String sender;
        String subject;
        Instant date;
        final StringBuilder body = new StringBuilder();
        final List<ParsedAttachment> attachments = new ArrayList<>();
    }

    private ParsedEmail parse(jakarta.mail.Message m) throws MessagingException, IOException {
        ParsedEmail out = new ParsedEmail();
        out.sender = formatAddress(m.getFrom());
        out.subject = m.getSubject();
        // Receipt time at our mailbox (IMAP INTERNALDATE), not the sender's clock: in pharmacovigilance
        // the receipt date starts the reporting timeline, and it's what the IMAP SINCE search filters on.
        Date date = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        out.date = date != null ? date.toInstant() : Instant.now();
        // Download the whole raw email once and parse it locally: one round trip instead of one
        // per MIME part, and no dependence on how a given server numbers nested (forwarded) parts.
        MimeMessage local = new MimeMessage((MimeMessage) m);
        out.body.append(textOf(local, out, false));
        return out;
    }

    /** Returns the readable text of a part, collecting any attachments found beneath it. */
    private String textOf(Part part, ParsedEmail out, boolean forwarded) throws MessagingException, IOException {
        if (part.isMimeType("message/rfc822")) {
            // Re-parse the forwarded email from its raw bytes rather than letting the IMAP server
            // serve its nested parts: servers differ in how well they handle nested BODYSTRUCTURE.
            jakarta.mail.Message fwd;
            try (InputStream in = part.getInputStream()) {
                fwd = new MimeMessage((Session) null, in);
            }
            String header = "\n\n---------- Forwarded message ----------\n"
                    + "From: " + formatAddress(fwd.getFrom()) + "\n"
                    + "Date: " + (fwd.getSentDate() != null ? fwd.getSentDate().toInstant() : "unknown") + "\n"
                    + "Subject: " + Objects.toString(fwd.getSubject(), "") + "\n\n";
            return header + textOf(fwd, out, true);
        }
        if (isAttachment(part)) {
            addAttachment(part, out, forwarded);
            return "";
        }
        if (part.isMimeType("text/plain")) {
            return readText(part);
        }
        if (part.isMimeType("text/html")) {
            return htmlToText(readText(part));
        }
        if (part.isMimeType("multipart/alternative")) {
            Multipart mp = (Multipart) part.getContent();
            // Prefer the plain-text rendering; otherwise the last (richest) alternative.
            for (int i = 0; i < mp.getCount(); i++) {
                if (mp.getBodyPart(i).isMimeType("text/plain")) {
                    return textOf(mp.getBodyPart(i), out, forwarded);
                }
            }
            return mp.getCount() == 0 ? "" : textOf(mp.getBodyPart(mp.getCount() - 1), out, forwarded);
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                String t = textOf(mp.getBodyPart(i), out, forwarded);
                if (!t.isBlank()) {
                    sb.append(sb.isEmpty() ? "" : "\n").append(t);
                }
            }
            return sb.toString();
        }
        // Any other inline, non-text part (e.g. an inline image with no filename) is an attachment too.
        addAttachment(part, out, forwarded);
        return "";
    }

    private boolean isAttachment(Part part) throws MessagingException {
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
    }

    private void addAttachment(Part part, ParsedEmail out, boolean forwarded) throws MessagingException, IOException {
        byte[] data;
        try (InputStream in = part.getInputStream()) {
            data = in.readAllBytes();
        }
        String mimeType = part.getContentType() != null
                ? part.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT)
                : "application/octet-stream";
        String filename = part.getFileName() != null ? MimeUtility.decodeText(part.getFileName()) : null;
        boolean looksLikePdf = "application/pdf".equals(mimeType)
                || (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf"));
        // Trust the bytes, not the label: a mislabelled file would only fail later in the AI service.
        boolean pdf = looksLikePdf && startsWithPdfMagic(data);
        if (looksLikePdf && !pdf) {
            log.warn("Attachment '{}' is labelled as PDF but is not a valid PDF - logging only", filename);
        }
        if (!StringUtils.hasText(filename)) {
            filename = "attachment-" + (out.attachments.size() + 1) + (pdf ? ".pdf" : "");
        }
        out.attachments.add(new ParsedAttachment(filename, mimeType, data, pdf, forwarded));
    }

    /** PDF readers accept the "%PDF-" header anywhere in the first 1 KB, so check the same window. */
    private static boolean startsWithPdfMagic(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 1024), StandardCharsets.ISO_8859_1);
        return head.contains("%PDF-");
    }

    private static String readText(Part part) throws MessagingException, IOException {
        try {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }
        } catch (IOException e) {
            // e.g. an unknown charset label - fall through to a lenient UTF-8 decode
        }
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String htmlToText(String html) {
        var doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        doc.select("br").after("\\n");
        doc.select("p, div, li, tr, h1, h2, h3, h4, h5, h6").before("\\n");
        String text = doc.text().replace("\\n", "\n");
        return text.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String formatAddress(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "unknown";
        }
        Address a = addresses[0];
        if (a instanceof InternetAddress ia) {
            // "Dr. Jane Roe <jane@clinic.example>" - the display name often carries the reporter's role.
            return StringUtils.hasText(ia.getPersonal()) ? ia.getPersonal() + " <" + ia.getAddress() + ">" : ia.getAddress();
        }
        return a.toString();
    }

    private String externalRef(Folder folder, jakarta.mail.Message m) throws MessagingException {
        String[] ids = m.getHeader("Message-ID");
        String ref;
        if (ids != null && ids.length > 0 && StringUtils.hasText(ids[0])) {
            ref = EXTERNAL_REF_PREFIX + ids[0].trim();
        } else if (folder instanceof UIDFolder uf) {
            // No Message-ID header (rare): fall back to the IMAP UID, which is stable for this folder.
            ref = EXTERNAL_REF_PREFIX + "uid:" + folder.getFullName() + ":" + uf.getUIDValidity() + ":" + uf.getUID(m);
        } else {
            throw new MessagingException("Email has neither a Message-ID nor an IMAP UID");
        }
        return ref.length() <= MAX_EXTERNAL_REF ? ref : EXTERNAL_REF_PREFIX + "sha256:" + sha256(ref);
    }

    private boolean isConfigured() {
        return StringUtils.hasText(props.mail().username()) && StringUtils.hasText(props.mail().password());
    }

    private static String safeSubject(jakarta.mail.Message m) {
        try {
            return m.getSubject();
        } catch (MessagingException e) {
            return "(unreadable subject)";
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class MailboxBusyException extends RuntimeException {
        public MailboxBusyException(String message) {
            super(message);
        }
    }
}
