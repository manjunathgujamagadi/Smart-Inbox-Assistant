package com.clinevo.smartinbox.dto.ai;

import java.util.List;

public record ScreenLiteratureRequest(
        Long attachmentId,
        String filename,
        List<AnalyzeMessageRequest.PageContext> pages
) {}
