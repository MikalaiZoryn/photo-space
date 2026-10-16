package com.photospace.jobs;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with "equal jitter": the delay before retry {@code n} is half of
 * {@code min(max, base * 2^(n-1))} plus a random amount up to the other half. Retries of jobs that failed
 * together spread out instead of hitting a recovering dependency at the same moment.
 */
final class Backoff {

    private Backoff() {}

    /** The delay after failed attempt {@code attempt} (1-based). */
    static Duration delay(int attempt, Duration base, Duration max, RandomGenerator random) {
        long baseMillis = base.toMillis();
        long maxMillis = max.toMillis();
        int doublings = Math.min(Math.max(attempt - 1, 0), 30);
        long ceiling = baseMillis > (maxMillis >> doublings) ? maxMillis : Math.min(maxMillis, baseMillis << doublings);
        long half = ceiling / 2;
        return Duration.ofMillis(half + random.nextLong(ceiling - half + 1));
    }
}
