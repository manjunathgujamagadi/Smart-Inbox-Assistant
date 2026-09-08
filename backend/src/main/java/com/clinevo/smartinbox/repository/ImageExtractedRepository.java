package com.clinevo.smartinbox.repository;

import com.clinevo.smartinbox.domain.ImageExtracted;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImageExtractedRepository extends JpaRepository<ImageExtracted, Long> {
    List<ImageExtracted> findByAttachmentId(Long attachmentId);
}
