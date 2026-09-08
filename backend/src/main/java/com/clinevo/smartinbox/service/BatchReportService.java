package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.domain.ProcessingJob;
import com.clinevo.smartinbox.domain.enums.JobStatus;
import com.clinevo.smartinbox.dto.api.BatchReportDto;
import com.clinevo.smartinbox.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reports per-document processing time for the required batch run
 * (Section 3E: "Process at least 10-15 sample documents automatically
 * and report how long each one takes").
 */
@Service
@RequiredArgsConstructor
public class BatchReportService {

    private final ProcessingJobRepository processingJobRepository;

    @Transactional(readOnly = true)
    public BatchReportDto buildReport() {
        List<ProcessingJob> jobs = processingJobRepository.findAll();

        long done = jobs.stream().filter(j -> j.getStatus() == JobStatus.DONE).count();
        long failed = jobs.stream().filter(j -> j.getStatus() == JobStatus.FAILED).count();

        List<Long> durations = jobs.stream()
                .map(ProcessingJob::getDurationMs)
                .filter(d -> d != null)
                .toList();

        double avg = durations.isEmpty() ? 0.0 : durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long min = durations.isEmpty() ? 0 : durations.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = durations.isEmpty() ? 0 : durations.stream().mapToLong(Long::longValue).max().orElse(0);

        List<BatchReportDto.JobEntry> entries = jobs.stream()
                .map(j -> new BatchReportDto.JobEntry(
                        j.getMessage().getId(),
                        j.getMessage().getSubject(),
                        j.getStatus().name(),
                        j.getDurationMs()))
                .toList();

        return new BatchReportDto(jobs.size(), (int) done, (int) failed, avg, min, max, entries);
    }
}
