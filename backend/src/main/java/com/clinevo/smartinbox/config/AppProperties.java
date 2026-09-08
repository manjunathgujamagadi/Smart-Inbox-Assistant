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

    public record Mail(String inboxFolder, long pollIntervalMs, boolean enabled) {}

    public record Storage(String root) {}

    public record Queue(int workerPoolSize) {}

    public record Reviewer(String defaultName) {}

    public record Batch(String defaultDir) {}
}
