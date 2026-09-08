package com.clinevo.smartinbox.web;

import com.clinevo.smartinbox.config.AppProperties;
import com.clinevo.smartinbox.dto.api.BatchReportDto;
import com.clinevo.smartinbox.service.BatchReportService;
import com.clinevo.smartinbox.service.LocalBatchIngestionService;
import com.clinevo.smartinbox.service.ProcessingQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Drives the required automated batch run over the synthetic test-data set
 * (Section 3E) and reports per-document timing. `run` ingests every email
 * fixture in a local directory (default: test-data/emails) and enqueues it
 * on the same processing pipeline used for real mailbox intake.
 */
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
@CrossOrigin
public class BatchController {

    private final LocalBatchIngestionService localBatchIngestionService;
    private final BatchReportService batchReportService;
    private final ProcessingQueueService processingQueueService;
    private final AppProperties props;

    @PostMapping("/retry-failed")
    public Map<String, Object> retryFailed() {
        int retried = processingQueueService.retryFailed();
        return Map.of("retried", retried);
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(required = false) String dir) throws IOException {
        if (dir == null || dir.isBlank()) {
            dir = props.batch().defaultDir();
        }
        List<Long> messageIds = localBatchIngestionService.ingestDirectory(Path.of(dir));
        return Map.of("ingested", messageIds.size(), "messageIds", messageIds);
    }

    @GetMapping("/report")
    public BatchReportDto report() {
        return batchReportService.buildReport();
    }
}
