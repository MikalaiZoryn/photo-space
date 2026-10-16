package com.photospace.identity;

import com.photospace.shared.web.NotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in discovery, the CSRF bootstrap, and the current user. Sign-out is handled by Spring Security. */
@RestController
class AuthController {

    private final boolean googleEnabled;
    private final boolean devLoginEnabled;
    private final IdentityService identityService;

    AuthController(
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            @Value("${app.auth.dev-login-enabled:false}") boolean devLoginEnabled,
            IdentityService identityService) {
        this.googleEnabled = clientRegistrations.getIfAvailable() != null;
        this.devLoginEnabled = devLoginEnabled;
        this.identityService = identityService;
    }

    /** Which sign-in methods the frontend should offer, and where Google sign-in starts. */
    @GetMapping("/api/auth/options")
    AuthOptions options() {
        return new AuthOptions(
                googleEnabled ? SecurityConfig.OAUTH2_AUTHORIZATION_BASE + "/" + IdentityService.GOOGLE : null,
                devLoginEnabled);
    }

    /** Makes sure the {@code XSRF-TOKEN} cookie is set. The SPA calls this before its first state change. */
    @GetMapping("/api/auth/csrf")
    ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken(); // Loading the deferred token writes the cookie.
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/me")
    UserProfile me(@AuthenticationPrincipal SignedInUser user) {
        return identityService.findProfile(user.userId()).orElseThrow(NotFoundException::new);
    }

    record AuthOptions(String googleSignInUrl, boolean devLogin) {}
}
