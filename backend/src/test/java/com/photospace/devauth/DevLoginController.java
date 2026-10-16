package com.photospace.devauth;

import com.photospace.identity.IdentityService;
import com.photospace.identity.SignInSessions;
import com.photospace.identity.SignedInUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signs in as a "dev" identity named by email, with no password. Dev identities use their own provider, so
 * they can never collide with or take over a Google identity. See {@link DevAuthConfiguration}.
 */
@RestController
@TestComponent // Kept out of component scanning in tests; DevAuthConfiguration imports it explicitly.
class DevLoginController {

    static final String PATH = "/api/dev/login";

    private final IdentityService identityService;
    private final SignInSessions signInSessions;

    DevLoginController(IdentityService identityService, SignInSessions signInSessions) {
        this.identityService = identityService;
        this.signInSessions = signInSessions;
    }

    @PostMapping(PATH)
    ResponseEntity<Void> login(
            @Valid @RequestBody DevLoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        UUID userId = identityService.signIn("dev", email, email, true);
        signInSessions.establish(new SignedInUser(userId), request, response);
        return ResponseEntity.noContent().build();
    }

    record DevLoginRequest(@NotBlank @Email String email) {}
}
