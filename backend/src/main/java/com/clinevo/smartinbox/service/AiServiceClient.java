package com.clinevo.smartinbox.service;

import com.clinevo.smartinbox.dto.ai.AnalyzeMessageRequest;
import com.clinevo.smartinbox.dto.ai.AnalyzeMessageResponse;
import com.clinevo.smartinbox.dto.ai.PdfProcessRequest;
import com.clinevo.smartinbox.dto.ai.PdfProcessResponse;
import com.clinevo.smartinbox.dto.ai.ScreenLiteratureRequest;
import com.clinevo.smartinbox.dto.ai.ScreenLiteratureResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AiServiceClient {

    private final WebClient aiServiceWebClient;

    public PdfProcessResponse processPdf(Long attachmentId, String filePath) {
        return aiServiceWebClient.post()
                .uri("/ai/process-pdf")
                .bodyValue(new PdfProcessRequest(attachmentId, filePath))
                .retrieve()
                .bodyToMono(PdfProcessResponse.class)
                .block(Duration.ofMinutes(3));
    }

    public AnalyzeMessageResponse analyzeMessage(AnalyzeMessageRequest request) {
        return aiServiceWebClient.post()
                .uri("/ai/analyze-message")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AnalyzeMessageResponse.class)
                .block(Duration.ofMinutes(2));
    }

    public ScreenLiteratureResponse screenLiterature(ScreenLiteratureRequest request) {
        return aiServiceWebClient.post()
                .uri("/ai/screen-literature")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ScreenLiteratureResponse.class)
                .block(Duration.ofMinutes(2));
    }
}
