package com.clinevo.smartinbox.web;

import com.clinevo.smartinbox.dto.api.MailStatusDto;
import com.clinevo.smartinbox.dto.api.MailStatusDto.MailFetchResultDto;
import com.clinevo.smartinbox.service.MailIngestionService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Live mailbox intake (Section 3A). The scheduler polls on its own; `fetch`
 * lets a reviewer (or a live demo) check the mailbox immediately.
 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
@CrossOrigin
public class MailController {

    private final MailIngestionService mailIngestionService;

    @GetMapping("/status")
    public MailStatusDto status() {
        return mailIngestionService.status();
    }

    @PostMapping("/fetch")
    public MailFetchResultDto fetch() throws MessagingException {
        return mailIngestionService.fetchAndIngestNewMessages();
    }
}
