package com.clinevo.smartinbox.web;

import com.clinevo.smartinbox.domain.Attachment;
import com.clinevo.smartinbox.repository.AttachmentRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

/**
 * Serves the raw PDF bytes for the Angular document viewer, streamed from the
 * shared storage volume rather than round-tripped through the database.
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
@CrossOrigin
public class AttachmentController {

    private final AttachmentRepository attachmentRepository;

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long id) {
        Attachment attachment = attachmentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Attachment not found: " + id));

        if (!attachment.isProcessable()) {
            throw new IllegalArgumentException("Attachment " + id + " was not stored (non-PDF, logged only)");
        }

        File file = new File(attachment.getStorageRef());
        if (!file.exists()) {
            throw new EntityNotFoundException("Stored file missing for attachment " + id);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.getFilename() + "\"")
                .body(new FileSystemResource(file));
    }
}
