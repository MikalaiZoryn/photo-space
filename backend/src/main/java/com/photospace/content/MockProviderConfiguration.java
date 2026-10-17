package com.photospace.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The default provider when no AI account is configured: local development and tests. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.content.provider", havingValue = "mock", matchIfMissing = true)
class MockProviderConfiguration {

    @Bean
    ContentGenerationProvider mockContentProvider(ContentProperties properties) {
        return new MockContentProvider(properties.mockDelay());
    }
}
