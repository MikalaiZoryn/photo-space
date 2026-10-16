package com.photospace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Users are keyed on (provider, subject); email is only a display snapshot. */
@IntegrationTest
class IdentityServiceIT {

    @Autowired
    private IdentityService identityService;

    @Test
    void sameSubjectIsTheSameUserEvenAfterEmailChanges() {
        String subject = "google-sub-" + UUID.randomUUID();

        UUID first = identityService.signIn(IdentityService.GOOGLE, subject, "old@example.com", true);
        UUID second = identityService.signIn(IdentityService.GOOGLE, subject, "new@example.com", true);

        assertThat(second).isEqualTo(first);
        assertThat(identityService.findProfile(first).orElseThrow().email()).isEqualTo("new@example.com");
    }

    @Test
    void sameEmailWithDifferentSubjectIsADifferentUser() {
        String email = "shared-" + UUID.randomUUID() + "@example.com";

        UUID first = identityService.signIn(IdentityService.GOOGLE, "sub-" + UUID.randomUUID(), email, true);
        UUID second = identityService.signIn(IdentityService.GOOGLE, "sub-" + UUID.randomUUID(), email, true);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void devIdentitiesNeverMatchGoogleIdentities() {
        String subject = "same-" + UUID.randomUUID() + "@example.com";

        UUID google = identityService.signIn(IdentityService.GOOGLE, subject, subject, true);
        UUID dev = identityService.signIn("dev", subject, subject, true);

        assertThat(dev).isNotEqualTo(google);
    }
}
