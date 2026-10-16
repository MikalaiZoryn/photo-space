package com.photospace.devauth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Development sign-in without Google, for local runs and integration tests.
 *
 * <p>This lives in {@code src/test} on purpose: it is not compiled into the application jar, so it cannot be
 * enabled in a deployed environment by any profile or property. It is active only through
 * {@code ./mvnw spring-boot:test-run} or tests that import it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(DevLoginController.class)
@PropertySource("classpath:dev-auth.properties")
public class DevAuthConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain devLoginSecurity(HttpSecurity http) {
        // Signing in needs no prior session or CSRF token; everything else goes through the main chain.
        return http.securityMatcher(DevLoginController.PATH)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .build();
    }
}
