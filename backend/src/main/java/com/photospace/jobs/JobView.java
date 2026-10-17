package com.photospace.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * A job as the API shows it. {@code lastError} is the stored safe summary (an error class or a fixed sentence).
 * {@code subjectId} is the record the job works on; its feature shows the outcome and offers recovery.
 */
public record JobView(
        UUID id,
        String type,
        UUID subjectId,
        JobStatus status,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {}
