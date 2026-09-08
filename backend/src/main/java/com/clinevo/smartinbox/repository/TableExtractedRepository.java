package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.TableExtracted;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TableExtractedRepository extends JpaRepository<TableExtracted, Long> {
    List<TableExtracted> findByAttachmentId(Long attachmentId);
}
