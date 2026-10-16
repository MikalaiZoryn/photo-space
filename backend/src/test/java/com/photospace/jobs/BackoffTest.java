package com.photospace.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofMinutes(15);

    @Test
    void doublesFromTheBaseUpToTheMaximum() {
        RandomGenerator highest = new FixedRandom(true);
        assertThat(Backoff.delay(1, BASE, MAX, highest)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Backoff.delay(2, BASE, MAX, highest)).isEqualTo(Duration.ofSeconds(60));
        assertThat(Backoff.delay(3, BASE, MAX, highest)).isEqualTo(Duration.ofSeconds(120));
        assertThat(Backoff.delay(6, BASE, MAX, highest)).isEqualTo(Duration.ofMinutes(15));
        assertThat(Backoff.delay(500, BASE, MAX, highest)).as("no overflow").isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void jitterKeepsAtLeastHalfTheDelay() {
        RandomGenerator lowest = new FixedRandom(false);
        assertThat(Backoff.delay(1, BASE, MAX, lowest)).isEqualTo(Duration.ofSeconds(15));
        assertThat(Backoff.delay(3, BASE, MAX, lowest)).isEqualTo(Duration.ofSeconds(60));

        RandomGenerator random = RandomGenerator.getDefault();
        for (int i = 0; i < 1000; i++) {
            assertThat(Backoff.delay(2, BASE, MAX, random)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
        }
    }

    /** Always returns the lowest or the highest value of the requested range. */
    private record FixedRandom(boolean highest) implements RandomGenerator {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return highest ? bound - 1 : 0;
        }
    }
}
