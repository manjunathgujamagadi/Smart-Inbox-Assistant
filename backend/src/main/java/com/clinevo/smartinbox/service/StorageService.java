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
            Path target = uniquePath(dir, safeName);
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new StorageException("Failed to store attachment " + filename + " for message " + messageId, e);
        }
    }

    /** Two attachments with the same name on one email (e.g. two "scan.pdf") must not overwrite each other. */
    private Path uniquePath(Path dir, String name) {
        Path target = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; Files.exists(target); i++) {
            target = dir.resolve(stem + "-" + i + ext);
        }
        return target;
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
