package com.clinevo.smartinbox.dto.api;

import java.time.Instant;

/** What the review screen shows about the live mailbox connection. */
public record MailStatusDto(
        boolean pollingEnabled,
        boolean configured,
        String mailbox,
        String folder,
        long pollIntervalMs,
        Instant lastPollAt,
        MailFetchResultDto lastResult,
        String lastError
) {
    public record MailFetchResultDto(
            Instant since,
            int scanned,
            int ingested,
            int alreadyIngested,
            int failed
    ) {}
}
