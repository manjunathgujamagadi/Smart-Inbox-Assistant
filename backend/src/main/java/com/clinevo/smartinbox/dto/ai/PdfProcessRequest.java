package com.clinevo.smartinbox.dto.ai;

public record PdfProcessRequest(
        Long attachmentId,
        String filePath
) {}
