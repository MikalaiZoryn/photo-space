package com.photospace.jobs;

import java.util.UUID;

/**
 * A claimed job as its handler sees it. {@code attempt} counts from 1. The subject is the record the job works
 * on, in {@code workspaceId}; handlers load it scoped by that workspace. {@code correlationId} is that of the
 * request that queued the job, if any.
 */
public record Job(
        UUID id, UUID workspaceId, String type, UUID subjectId, int attempt, int maxAttempts, String correlationId) {}
