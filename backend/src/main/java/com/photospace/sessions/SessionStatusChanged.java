package com.photospace.sessions;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that changes a session's status, so a listener's writes commit or roll back
 * with the change. Listeners must not call external systems; they queue jobs instead.
 */
public record SessionStatusChanged(
        UUID workspaceId,
        UUID sessionId,
        UUID clientId,
        SessionStatus from,
        SessionStatus to,
        UUID actorId,
        Instant occurredAt) {}
