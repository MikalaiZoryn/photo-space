package com.photospace.content.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.photospace.content.ContentGenerationProvider;
import com.photospace.content.ContentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Claude as the content provider, with {@code app.content.provider=anthropic}. The API key comes from
 * {@code ANTHROPIC_API_KEY} and never leaves the server. Startup fails without one, rather than failing every
 * batch later.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.content.provider", havingValue = "anthropic")
class AnthropicProviderConfiguration {

    @Bean(destroyMethod = "close")
    AnthropicClient anthropicClient(ContentProperties properties) {
        ContentProperties.Anthropic anthropic = properties.anthropic();
        if (anthropic.apiKey() == null || anthropic.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Set ANTHROPIC_API_KEY (app.content.anthropic.api-key) to use app.content.provider=anthropic");
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(anthropic.apiKey())
                .timeout(anthropic.timeout())
                .maxRetries(anthropic.maxRetries());
        if (anthropic.baseUrl() != null && !anthropic.baseUrl().toString().isBlank()) {
            builder.baseUrl(anthropic.baseUrl().toString());
        }
        return builder.build();
    }

    @Bean
    ContentGenerationProvider anthropicContentProvider(AnthropicClient client, ContentProperties properties) {
        return new AnthropicContentProvider(client, properties.anthropic());
    }
}
