package com.photospace.support;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Polls for asynchronous results, such as background jobs. */
public final class Eventually {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofMillis(100);

    private Eventually() {}

    /** Returns the first value that satisfies {@code done}; fails with the last value after 30 seconds. */
    public static <T> T await(Supplier<T> value, Predicate<T> done) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        T last = value.get();
        while (!done.test(last)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + TIMEOUT + "; last value: " + last);
            }
            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            last = value.get();
        }
        return last;
    }
}
