package com.photospace.sessions;

import java.util.List;

/**
 * The lifecycle of a photography session.
 *
 * <pre>
 * INQUIRY ──► BOOKED ──► COMPLETED ──► DELIVERED
 *    │  ▲        │
 *    ▼  │        │
 * CANCELLED ◄────┘
 * </pre>
 *
 * A cancelled session can be reopened as an inquiry. Delivered is final. Every status from booked on needs a
 * scheduled start.
 */
public enum SessionStatus {
    /** A request or tentative booking; the date may not be set yet. */
    INQUIRY,
    /** Confirmed with the client for a date. */
    BOOKED,
    /** The shoot took place. */
    COMPLETED,
    /** The photos were delivered to the client. */
    DELIVERED,
    /** Will not take place. */
    CANCELLED;

    /** The statuses this one may change to, in the order the UI offers them. */
    public List<SessionStatus> next() {
        return switch (this) {
            case INQUIRY -> List.of(BOOKED, CANCELLED);
            case BOOKED -> List.of(COMPLETED, CANCELLED);
            case COMPLETED -> List.of(DELIVERED);
            case DELIVERED -> List.of();
            case CANCELLED -> List.of(INQUIRY);
        };
    }

    public boolean canChangeTo(SessionStatus target) {
        return next().contains(target);
    }

    /** Whether a session in this status must have a scheduled start. */
    public boolean requiresStart() {
        return this != INQUIRY && this != CANCELLED;
    }
}
