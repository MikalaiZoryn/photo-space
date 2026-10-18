package com.photospace.content;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The provider integration tests use: the mock provider without its delay, plus failures a test queues up and a
 * record of every request, so tests can check exactly what would have been sent to an AI provider.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContentProvider {

    /** Failures to throw, one per call, before answering normally again. */
    public static final Queue<ContentGenerationException.Kind> FAILURES = new ConcurrentLinkedQueue<>();
    public static final List<GenerationRequest> REQUESTS = new CopyOnWriteArrayList<>();
    public static final List<PlanRequest> PLAN_REQUESTS = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    ContentGenerationProvider testContentProvider() {
        MockContentProvider mock = new MockContentProvider(Duration.ZERO);
        return new ContentGenerationProvider() {
            @Override
            public String provider() {
                return mock.provider();
            }

            @Override
            public String model() {
                return mock.model();
            }

            @Override
            public GenerationResult generate(GenerationRequest request) {
                REQUESTS.add(request);
                ContentGenerationException.Kind failure = FAILURES.poll();
                if (failure != null) {
                    throw new ContentGenerationException(failure);
                }
                return mock.generate(request);
            }

            @Override
            public PlanResult plan(PlanRequest request) {
                PLAN_REQUESTS.add(request);
                ContentGenerationException.Kind failure = FAILURES.poll();
                if (failure != null) {
                    throw new ContentGenerationException(failure);
                }
                return mock.plan(request);
            }
        };
    }
}
