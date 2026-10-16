package com.photospace.support;

import com.photospace.TestcontainersConfiguration;
import com.photospace.devauth.DevAuthConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The full application on a random port against a clean PostgreSQL container, with dev sign-in and a fake
 * Google client registration (enough to test the redirect to Google, never to complete it). All integration
 * tests share this one configuration, so they share one cached application context and container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.auth.google.client-id=test-client-id",
            "app.auth.google.client-secret=test-client-secret"
        })
@Import({TestcontainersConfiguration.class, DevAuthConfiguration.class})
public @interface IntegrationTest {}
