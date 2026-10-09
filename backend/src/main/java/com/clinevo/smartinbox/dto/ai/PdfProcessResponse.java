package com.clinevo.smartinbox.dto.ai;

import java.util.List;

public record PdfProcessResponse(
        String flavor,               // DIGITAL | SCANNED_HANDWRITTEN | ARTICLE | NON_ENGLISH
        String detectedLanguage,
        Integer pageCount,
        List<PageDto> pages,
        String fullText,
        List<TableDto> tables,
        List<ImageDto> images,
        String summary,
        Double confidence
) {
    public record PageDto(int pageNumber, String text, String translatedText, Double confidence) {}

    public record TableDto(int pageNumber, List<String> headers, List<List<String>> rows) {}

    public record ImageDto(int pageNumber, String description, boolean flaggedForReview) {}
}
