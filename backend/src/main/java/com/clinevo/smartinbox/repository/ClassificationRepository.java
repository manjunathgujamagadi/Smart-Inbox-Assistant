package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.Classification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassificationRepository extends JpaRepository<Classification, Long> {
    List<Classification> findByMessageId(Long messageId);
}
