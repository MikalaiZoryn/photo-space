package com.photospace.invitations;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Workspace invitations (D20). {@code appUrl} is where people open the app; invitation links point there. An
 * invitation link works for {@code validFor} after each send. A workspace has at most {@code maxPending} open
 * invitations, and the same invitation is sent at most once per {@code resendCooldown}.
 */
@Validated
@ConfigurationProperties("app.invitations")
record InvitationProperties(
        @NotNull URI appUrl,
        @NotNull Duration validFor,
        @Min(1) @Max(1000) int maxPending,
        @NotNull Duration resendCooldown) {}
