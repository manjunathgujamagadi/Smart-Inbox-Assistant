package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes attachment bytes to a filesystem location shared (via a Docker volume)
 * between the backend and ai-service containers, so the AI service can read
 * PDFs directly by path instead of round-tripping bytes over HTTP.
 */
@Service
@RequiredArgsConstructor
public class StorageService {

    private final AppProperties props;

    public String saveAttachment(Long messageId, String filename, InputStream data) {
        try {
            Path dir = Path.of(props.storage().root(), "messages", String.valueOf(messageId));
            Files.createDirectories(dir);
            String safeName = filename == null || filename.isBlank() ? "attachment.bin" : filename.replaceAll("[/\\\\]", "_");
            Path target = dir.resolve(safeName);
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new StorageException("Failed to store attachment " + filename + " for message " + messageId, e);
        }
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
