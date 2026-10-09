package com.clinevo.smartinbox.web;

import com.clinevo.smartinbox.service.MailIngestionService;
import jakarta.mail.MessagingException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MailIngestionService.MailboxBusyException.class)
    public ResponseEntity<Map<String, String>> mailboxBusy(MailIngestionService.MailboxBusyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** IMAP connect/login failures: the mailbox, not this API, is what's broken. */
    @ExceptionHandler(MessagingException.class)
    public ResponseEntity<Map<String, String>> mailbox(MessagingException e) {
        log.warn("Mailbox error", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Mailbox error: " + e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> generic(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal error: " + e.getMessage()));
    }
}
