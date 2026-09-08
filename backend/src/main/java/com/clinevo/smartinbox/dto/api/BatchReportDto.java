package com.clinevo.smartinbox.dto.api;

import java.util.List;

public record BatchReportDto(
        int totalDocuments,
        int done,
        int failed,
        double avgDurationMs,
        long minDurationMs,
        long maxDurationMs,
        List<JobEntry> jobs
) {
    public record JobEntry(Long messageId, String subject, String status, Long durationMs) {}
}
