package com.photospace.support;

import com.photospace.TestcontainersConfiguration;
import com.photospace.content.TestContentProvider;
import com.photospace.devauth.DevAuthConfiguration;
import com.photospace.jobs.TestJobHandler;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The full application on a random port against clean PostgreSQL, S3, and SQS containers, with dev sign-in and
 * a fake Google client registration (enough to test the redirect to Google, never to complete it). Jobs are
 * dispatched, polled, and retried quickly, a test-only job type is registered, and AI content comes from a
 * scriptable mock provider ({@link TestContentProvider}). All integration tests share
 * this one configuration, so they share one cached application context and set of containers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.auth.google.client-id=test-client-id",
            "app.auth.google.client-secret=test-client-secret",
            "app.jobs.dispatcher.interval=100ms",
            "app.jobs.worker.wait-time=1s",
            "app.jobs.retry.max-attempts=3",
            "app.jobs.retry.base-delay=100ms",
            "app.jobs.retry.max-delay=400ms"
        })
@Import({TestcontainersConfiguration.class, DevAuthConfiguration.class, TestJobHandler.class, TestContentProvider.class})
public @interface IntegrationTest {}
