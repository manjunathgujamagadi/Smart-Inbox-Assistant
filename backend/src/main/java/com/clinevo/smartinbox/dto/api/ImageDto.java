package com.clinevo.smartinbox.dto.api;

public record ImageDto(Long id, Long attachmentId, Integer pageNumber, String description, boolean flaggedForReview) {}
