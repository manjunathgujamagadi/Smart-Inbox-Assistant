package com.clinevo.smartinbox.service;

/** Published after enqueue()'s transaction commits; see ProcessingQueueService. */
public record JobEnqueuedEvent(Long jobId) {}
