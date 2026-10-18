package com.photospace.galleries;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that publishes a gallery or takes it back to draft, so a listener's writes
 * commit or roll back with the change. Listeners must not call external systems; they queue jobs instead.
 */
public record GalleryStatusChanged(
        UUID workspaceId, UUID galleryId, UUID sessionId, GalleryStatus to, Instant occurredAt) {}
