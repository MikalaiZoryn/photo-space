package com.photospace.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Registers Google as the OIDC provider, only when {@code GOOGLE_CLIENT_ID} is set. Without it the app still
 * starts, and Google sign-in is simply unavailable.
 *
 * <p>Login asks only for {@code openid email}. Gmail and Calendar scopes belong to separate, later opt-in
 * connection flows and must never be added here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${app.auth.google.client-id:}'.isBlank()")
class GoogleLoginConfiguration {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.auth.google.client-id}") String clientId,
            @Value("${app.auth.google.client-secret:}") String clientSecret) {
        if (clientSecret.isBlank()) {
            throw new IllegalStateException("GOOGLE_CLIENT_SECRET must be set when GOOGLE_CLIENT_ID is set");
        }
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE
                .getBuilder(IdentityService.GOOGLE)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .scope("openid", "email")
                .redirectUri("{baseUrl}" + SecurityConfig.OAUTH2_CALLBACK_BASE + "/{registrationId}")
                .build());
    }
}
