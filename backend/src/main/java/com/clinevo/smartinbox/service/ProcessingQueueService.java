package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.Message;
import com.clinevo.smartinbox.domain.ProcessingJob;
import com.clinevo.smartinbox.domain.enums.EntityType;
import com.clinevo.smartinbox.domain.enums.JobStatus;
import com.clinevo.smartinbox.domain.enums.MessageStatus;
import com.clinevo.smartinbox.repository.MessageRepository;
import com.clinevo.smartinbox.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The "simple queue, not pure synchronous calls" required by the spec: enqueue()
 * persists a PENDING job row immediately (durable, queryable) and publishes an
 * event; the actual work only starts once that transaction commits, on a
 * separate async worker thread with no inherited transaction (see AsyncConfig).
 *
 * This indirection (event + AFTER_COMMIT, instead of enqueue() calling an
 * @Async method directly) matters: a direct call would be a self-invocation
 * that Spring's proxy-based @Async silently ignores, so the "async" work would
 * actually run inline, inside enqueue()'s own transaction. If that work then
 * throws (e.g. the AI service call fails), the @Transactional processing
 * method marks that *shared* transaction rollback-only before the failure is
 * caught - so by the time enqueue() tries to commit, the whole transaction
 * (including the job's FAILED status and audit entries) silently rolls back,
 * leaving the message stuck at RECEIVED with no visible error. Routing through
 * a transactional event listener gives the async work its own transaction
 * boundary, so a failure there can never poison the caller's transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessingQueueService {

    private final ProcessingJobRepository processingJobRepository;
    private final MessageRepository messageRepository;
    private final DocumentProcessingService documentProcessingService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ProcessingJob enqueue(Message message) {
        message.setStatus(MessageStatus.QUEUED);
        messageRepository.save(message);

        ProcessingJob job = new ProcessingJob();
        job.setMessage(message);
        job.setStatus(JobStatus.PENDING);
        job = processingJobRepository.save(job);

        auditService.logSystem(EntityType.PROCESSING_JOB, job.getId(), "JOB_ENQUEUED", Map.of("messageId", message.getId()));

        eventPublisher.publishEvent(new JobEnqueuedEvent(job.getId()));
        return job;
    }

    /**
     * Re-enqueues every message currently sitting at FAILED (e.g. after a transient
     * AI-service network error) as a fresh job. The prior FAILED job row is left in
     * place - a message can have more than one processing_jobs row over time, which
     * doubles as retry history in the audit trail rather than something to hide.
     */
    @Transactional
    public int retryFailed() {
        List<Message> failed = messageRepository.findByStatusOrderByReceivedAtAsc(MessageStatus.FAILED);
        for (Message message : failed) {
            enqueue(message);
        }
        return failed.size();
    }

    @Async("processingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobEnqueued(JobEnqueuedEvent event) {
        Long jobId = event.jobId();
        ProcessingJob job = processingJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Processing job {} disappeared before it could run", jobId);
            return;
        }
        // job.getMessage() is a lazy proxy; .getId() reads the FK without a query,
        // but touching any other field would need a session that's already closed
        // (findById()'s own transaction ended when it returned). Everything past
        // this point re-fetches the message fresh inside whichever short-lived
        // transaction needs it, instead of reusing this detached reference.
        Long messageId = job.getMessage().getId();

        Instant start = Instant.now();
        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(start);
        processingJobRepository.save(job);
        setMessageStatus(messageId, MessageStatus.PROCESSING);

        try {
            documentProcessingService.process(messageId);

            Instant end = Instant.now();
            job.setStatus(JobStatus.DONE);
            job.setCompletedAt(end);
            job.setDurationMs(end.toEpochMilli() - start.toEpochMilli());
            processingJobRepository.save(job);

            setMessageStatus(messageId, MessageStatus.PROCESSED);

            auditService.logSystem(EntityType.PROCESSING_JOB, job.getId(), "JOB_COMPLETED",
                    Map.of("durationMs", job.getDurationMs()));
        } catch (Exception e) {
            log.error("Processing failed for message {}", messageId, e);
            Instant end = Instant.now();
            job.setStatus(JobStatus.FAILED);
            job.setCompletedAt(end);
            job.setDurationMs(end.toEpochMilli() - start.toEpochMilli());
            job.setError(e.getMessage());
            processingJobRepository.save(job);

            setMessageStatus(messageId, MessageStatus.FAILED);

            auditService.logSystem(EntityType.PROCESSING_JOB, job.getId(), "JOB_FAILED",
                    Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void setMessageStatus(Long messageId, MessageStatus status) {
        messageRepository.findById(messageId).ifPresent(m -> {
            m.setStatus(status);
            messageRepository.save(m);
        });
    }
}
