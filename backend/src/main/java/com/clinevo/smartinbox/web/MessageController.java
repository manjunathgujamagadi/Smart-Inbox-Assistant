package com.clinevo.smartinbox.web;

import com.clinevo.smartinbox.dto.api.MessageDetailDto;
import com.clinevo.smartinbox.dto.api.MessageSummaryDto;
import com.clinevo.smartinbox.dto.api.ReviewRequest;
import com.clinevo.smartinbox.service.MessageQueryService;
import com.clinevo.smartinbox.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
@CrossOrigin
public class MessageController {

    private final MessageQueryService messageQueryService;
    private final ReviewService reviewService;

    @GetMapping
    public List<MessageSummaryDto> list() {
        return messageQueryService.listQueue();
    }

    @GetMapping("/{id}")
    public MessageDetailDto detail(@PathVariable Long id) {
        return messageQueryService.getDetail(id);
    }

    @PostMapping("/{id}/review")
    public MessageDetailDto review(@PathVariable Long id, @RequestBody ReviewRequest request) {
        reviewService.applyReview(id, request);
        return messageQueryService.getDetail(id);
    }
}
