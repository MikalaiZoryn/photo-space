package com.photospace.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class OidcLoginSuccessHandlerTest {

    private final IdentityService identityService = mock(IdentityService.class);
    private final OidcLoginSuccessHandler handler =
            new OidcLoginSuccessHandler(identityService, new SignInSessions());

    @Test
    void provisionsUserByGoogleSubjectAndStoresOnlyTheUserIdInTheSession() throws Exception {
        UUID userId = UUID.randomUUID();
        given(identityService.signIn("google", "google-sub-123", "photographer@example.com", true))
                .willReturn(userId);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            handler.onAuthenticationSuccess(request, response, token("google"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        SecurityContext stored = (SecurityContext) request.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored.getAuthentication().getPrincipal()).isEqualTo(new SignedInUser(userId));
        assertThat(stored.getAuthentication().getCredentials()).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void rejectsRegistrationsOtherThanGoogle() {
        assertThatThrownBy(() -> handler.onAuthenticationSuccess(
                        new MockHttpServletRequest(), new MockHttpServletResponse(), token("other")))
                .isInstanceOf(IllegalStateException.class);
        verify(identityService, never()).signIn(any(), any(), any(), anyBoolean());
    }

    private static OAuth2AuthenticationToken token(String registrationId) {
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("sub", "google-sub-123", "email", "photographer@example.com", "email_verified", true));
        DefaultOidcUser user = new DefaultOidcUser(List.of(), idToken);
        return new OAuth2AuthenticationToken(user, List.of(), registrationId);
    }
}
