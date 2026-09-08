package com.clinevo.smartinbox.dto.ai;

import java.util.List;

public record AnalyzeMessageRequest(
        MessageContext message,
        List<AttachmentContext> attachments
) {
    public record MessageContext(String sender, String subject, String bodyText, String receivedAt) {}

    public record AttachmentContext(
            Long attachmentId,
            String filename,
            String summary,
            String flavor,
            List<PageContext> pages   // per-page text so the model can cite exact page numbers
    ) {}

    public record PageContext(int pageNumber, String text) {}
}
