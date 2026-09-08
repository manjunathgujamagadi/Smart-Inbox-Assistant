package com.clinevo.smartinbox.dto.api;

public record AttachmentDto(
        Long id,
        String filename,
        String mimeType,
        boolean processable,
        String pdfFlavor,
        String detectedLanguage,
        Integer pageCount,
        boolean processed,
        String summary
) {}
