package com.photospace.content;

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
 * AI content generation (D16).
 *
 * @param provider which provider writes drafts: {@code mock} (local, no network) or {@code anthropic}
 * @param maxPhotos the most photos one batch may send to the provider
 * @param mockDelay how long the mock provider pretends to think, so the waiting state is visible locally
 * @param plans automatic session plans (Milestone 13)
 */
@Validated
@ConfigurationProperties("app.content")
public record ContentProperties(
        @NotNull Provider provider,
        @Min(1) @Max(20) int maxPhotos,
        @NotNull Duration mockDelay,
        @Valid @NotNull Anthropic anthropic,
        @Valid @NotNull Plans plans) {

    public enum Provider {
        MOCK,
        ANTHROPIC
    }

    /**
     * Session plans. A plan is proposed once a session's photos have settled: none became ready for
     * {@code settleAfter}. Only sessions whose last photo became ready within {@code lookback} are considered, so
     * old sessions never get plans all at once. A plan sends at most {@code maxPhotos} previews, spread over the
     * session. {@code enabled} turns the automatic proposals off for this process.
     */
    public record Plans(
            boolean enabled,
            @NotNull Duration settleAfter,
            @NotNull Duration lookback,
            @Min(1) @Max(40) int maxPhotos) {}

    /**
     * The Anthropic adapter. {@code apiKey} is required only when it is the provider. {@code baseUrl} is set only
     * for tests against a stub server. One request may take up to {@code timeout}, and the SDK retries it
     * {@code maxRetries} times before the job's own backoff takes over.
     */
    public record Anthropic(
            String apiKey,
            @NotBlank String model,
            @NotBlank String effort,
            @NotNull Duration timeout,
            @Min(1024) @Max(128000) int maxTokens,
            @Min(0) @Max(5) int maxRetries,
            URI baseUrl) {

        @Override
        public String toString() {
            // Never print the key, for example in a bind or validation error.
            return "Anthropic[model=" + model + ", effort=" + effort + ", timeout=" + timeout + ", apiKey="
                    + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<set>") + "]";
        }
    }
}
