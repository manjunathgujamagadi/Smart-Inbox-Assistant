package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.LiteratureCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiteratureCaseRepository extends JpaRepository<LiteratureCase, Long> {
    List<LiteratureCase> findByMessageIdOrderByCaseNumberAsc(Long messageId);
}
