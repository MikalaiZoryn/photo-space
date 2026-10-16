package com.photospace.jobs;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A test-only job type, {@value #TYPE}, whose behavior each test sets per subject: fail a given number of
 * times, then succeed. It records every run and every give-up.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJobHandler {

    public static final String TYPE = "test-flaky";

    /** Failures to throw before succeeding, by subject. Subjects not listed succeed at once. */
    public static final Map<UUID, Integer> FAILURES_BEFORE_SUCCESS = new ConcurrentHashMap<>();
    public static final Map<UUID, AtomicInteger> RUNS = new ConcurrentHashMap<>();
    public static final Set<UUID> GAVE_UP = ConcurrentHashMap.newKeySet();

    @Bean
    JobHandler testFlakyJobHandler() {
        return new JobHandler() {
            @Override
            public String type() {
                return TYPE;
            }

            @Override
            public void run(Job job) {
                int run = RUNS.computeIfAbsent(job.subjectId(), id -> new AtomicInteger()).incrementAndGet();
                if (run <= FAILURES_BEFORE_SUCCESS.getOrDefault(job.subjectId(), 0)) {
                    throw new IllegalStateException("secret-looking detail that must not be stored");
                }
            }

            @Override
            public void giveUp(Job job) {
                GAVE_UP.add(job.subjectId());
            }
        };
    }
}
