package com.photospace.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Runs after Spring Security has validated the OIDC response (state, nonce, ID token signature, issuer,
 * audience, and expiry). Provisions the user by Google {@code sub} and starts the application session.
 */
class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

    private final IdentityService identityService;
    private final SignInSessions signInSessions;

    OidcLoginSuccessHandler(IdentityService identityService, SignInSessions signInSessions) {
        this.identityService = identityService;
        this.signInSessions = signInSessions;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !IdentityService.GOOGLE.equals(token.getAuthorizedClientRegistrationId())
                || !(token.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new IllegalStateException("Unsupported sign-in authentication");
        }

        UUID userId = identityService.signIn(
                IdentityService.GOOGLE,
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()));
        signInSessions.establish(new SignedInUser(userId), request, response);
        log.info("User {} signed in with {}", userId, IdentityService.GOOGLE);
        response.sendRedirect(request.getContextPath() + "/");
    }
}
