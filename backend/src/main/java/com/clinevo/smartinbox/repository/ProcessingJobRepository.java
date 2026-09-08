package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.ProcessingJob;
import com.clinevo.smartinbox.domain.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    List<ProcessingJob> findByStatusOrderByCreatedAtAsc(JobStatus status);
    Optional<ProcessingJob> findFirstByMessageIdOrderByCreatedAtDesc(Long messageId);
    List<ProcessingJob> findByStatus(JobStatus status);
}
