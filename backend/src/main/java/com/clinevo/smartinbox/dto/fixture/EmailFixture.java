package com.clinevo.smartinbox.dto.fixture;

import java.util.List;

/**
 * Local test-data fixture describing one synthetic email, used both to seed
 * the walking skeleton during development and to drive the required batch
 * run over 10-15 sample documents (Section 3E "handle a real batch").
 * Attachment paths are resolved relative to the fixture's own directory.
 */
public record EmailFixture(
        String sender,
        String subject,
        String receivedAt,   // ISO-8601 instant
        String bodyText,
        List<String> attachments,
        String note          // free-text, e.g. which test-data category this satisfies (docs only)
) {}
