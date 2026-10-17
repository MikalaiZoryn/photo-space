package com.photospace.inquiries;

import java.util.Set;

/**
 * An inquiry's lifecycle. {@code RECEIVED} until the matching job has looked for clients with the same email,
 * then {@code NEW} (no match) or {@code NEEDS_REVIEW} (a match). A member then associates it with a client
 * ({@code ASSOCIATED}, final) or dismisses it ({@code DISMISSED}, which can be reopened).
 */
public enum InquiryStatus {
    RECEIVED,
    NEW,
    NEEDS_REVIEW,
    ASSOCIATED,
    DISMISSED;

    /** The statuses a member can associate or dismiss an inquiry from. */
    static final Set<InquiryStatus> OPEN = Set.of(NEW, NEEDS_REVIEW);
}
