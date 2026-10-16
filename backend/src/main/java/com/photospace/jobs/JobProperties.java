package com.photospace.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The job queue and the two job roles. The same jar runs both: the API service enables the dispatcher, and the
 * worker service enables the worker (locally, one process does both). {@code sqsEndpoint} is set only for a
 * local SQS emulator.
 */
@Validated
@ConfigurationProperties("app.jobs")
public record JobProperties(
        @NotBlank String queueUrl,
        @NotBlank String region,
        URI sqsEndpoint,
        @Valid @NotNull Dispatcher dispatcher,
        @Valid @NotNull Worker worker,
        @Valid @NotNull Retry retry) {

    boolean usesEmulator() {
        return sqsEndpoint != null && !sqsEndpoint.toString().isBlank();
    }

    /**
     * Hands due jobs to the queue every {@code interval}. A job handed over more than {@code staleAfter} ago
     * that has still not run (a lost message, or a worker that died mid-job) is handed over again.
     */
    public record Dispatcher(boolean enabled, @NotNull Duration interval, @Min(1) @Max(100) int batchSize,
            @NotNull Duration staleAfter) {}

    /**
     * Receives up to {@code batchSize} messages per poll, waiting up to {@code waitTime} for one. A claimed job
     * is leased for {@code lease}; the queue's visibility timeout must be longer.
     */
    public record Worker(boolean enabled, @NotNull Duration waitTime, @Min(1) @Max(10) int batchSize,
            @NotNull Duration lease) {}

    /** Attempts per job, and exponential backoff with jitter between them. */
    public record Retry(@Min(1) @Max(20) int maxAttempts, @NotNull Duration baseDelay, @NotNull Duration maxDelay) {}
}
