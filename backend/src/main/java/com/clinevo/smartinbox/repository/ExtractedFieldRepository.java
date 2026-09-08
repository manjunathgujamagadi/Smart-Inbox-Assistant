package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.ExtractedField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExtractedFieldRepository extends JpaRepository<ExtractedField, Long> {
    List<ExtractedField> findByMessageId(Long messageId);
}
