package com.photospace.identity;

import com.photospace.shared.web.SecurityProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * HTTP security for the API.
 *
 * <ul>
 *   <li>Everything requires an authenticated session except health, the sign-in endpoints, errors, and the
 *       gallery visitor API under {@code /api/public/}, which authorizes each request by its share link token.
 *   <li>Unauthenticated API calls get 401 (never a redirect) and do not create a session.
 *   <li>State-changing requests need the CSRF token: the SPA reads the {@code XSRF-TOKEN} cookie and sends it
 *       back in the {@code X-XSRF-TOKEN} header.
 *   <li>Google sign-in is wired only when {@link GoogleLoginConfiguration} is active.
 * </ul>
 *
 * Workspace membership is enforced separately by the workspaces module, per request.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String OAUTH2_AUTHORIZATION_BASE = "/api/auth/oauth2/authorization";
    static final String OAUTH2_CALLBACK_BASE = "/api/auth/oauth2/callback";

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            SignInSessions signInSessions,
            IdentityService identityService,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/options",
                                "/api/auth/csrf",
                                "/api/public/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf.spa())
                .securityContext(context -> context.securityContextRepository(signInSessions.repository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(SecurityProblemResponses.unauthorized())
                        .accessDeniedHandler(SecurityProblemResponses.forbidden()))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));

        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations != null) {
            http.oauth2Login(login -> login
                    .clientRegistrationRepository(registrations)
                    // The SPA is the login page; this also turns off Spring's generated /login page.
                    .loginPage("/")
                    .authorizationEndpoint(endpoint -> endpoint.baseUri(OAUTH2_AUTHORIZATION_BASE))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(OAUTH2_CALLBACK_BASE + "/*"))
                    .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                    .successHandler(new OidcLoginSuccessHandler(identityService, signInSessions))
                    .failureHandler(new SimpleUrlAuthenticationFailureHandler("/?signInError")));
        }
        return http.build();
    }

    /**
     * Sign-in needs only the validated ID token. The access token Google returns is for {@code openid email}
     * alone and is not kept anywhere, so it never reaches the session store.
     */
    private static final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String clientRegistrationId, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient authorizedClient,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {}

        @Override
        public void removeAuthorizedClient(
                String clientRegistrationId,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {}
    }
}
