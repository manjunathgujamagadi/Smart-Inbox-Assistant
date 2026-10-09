package com.clinevo.smartinbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        AiService aiService,
        Mail mail,
        Storage storage,
        Queue queue,
        Reviewer reviewer,
        Batch batch
) {
    public record AiService(String baseUrl, int timeoutSeconds) {}

    /**
     * @param ingestSince optional ISO date (e.g. 2026-09-01): never ingest mail received before
     *                    this day. Blank = only mail from the day before first startup onwards,
     *                    so pointing the app at a mailbox doesn't pull in its entire history.
     * @param sslTrust    optional hosts whose TLS certificate is trusted without CA validation
     *                    (self-signed corporate or local test IMAP servers). Blank for Gmail/Outlook.
     */
    public record Mail(boolean enabled, String host, int port, String username, String password,
                       String inboxFolder, long pollIntervalMs, String ingestSince, String sslTrust) {}

    public record Storage(String root) {}

    public record Queue(int workerPoolSize) {}

    public record Reviewer(String defaultName) {}

    public record Batch(String defaultDir) {}
}
