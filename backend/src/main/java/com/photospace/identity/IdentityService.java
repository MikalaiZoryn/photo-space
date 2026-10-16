package com.photospace.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maps external identities to application users. Users are keyed on (provider, subject), never on email. */
@Service
public class IdentityService {

    public static final String GOOGLE = "google";

    private final JdbcClient jdbc;

    public IdentityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the user linked to this external identity, creating both on first sign-in. The email snapshot is
     * refreshed on every sign-in. Two concurrent first sign-ins for the same subject cannot create two users:
     * the unique (provider, subject) constraint makes the second transaction fail and roll back.
     */
    @Transactional
    public UUID signIn(String provider, String subject, String email, boolean emailVerified) {
        Optional<UUID> existing = jdbc.sql("""
                        UPDATE external_identities
                           SET email = :email, email_verified = :emailVerified, last_login_at = now(), updated_at = now()
                         WHERE provider = :provider AND subject = :subject
                        RETURNING user_id
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .param("email", email)
                .param("emailVerified", emailVerified)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID userId = jdbc.sql("INSERT INTO users (display_name) VALUES (:displayName) RETURNING id")
                .param("displayName", email != null && !email.isBlank() ? email : "User")
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO external_identities (user_id, provider, subject, email, email_verified)
                        VALUES (:userId, :provider, :subject, :email, :emailVerified)
                        """)
                .param("userId", userId)
                .param("provider", provider)
                .param("subject", subject)
                .param("email", email)
                .param("emailVerified", emailVerified)
                .update();
        return userId;
    }

    @Transactional(readOnly = true)
    public Optional<UserProfile> findProfile(UUID userId) {
        return jdbc.sql("""
                        SELECT u.id, u.display_name,
                               (SELECT ei.email FROM external_identities ei
                                 WHERE ei.user_id = u.id ORDER BY ei.last_login_at DESC LIMIT 1) AS email
                          FROM users u
                         WHERE u.id = :userId
                        """)
                .param("userId", userId)
                .query((rs, i) -> new UserProfile(
                        rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("email")))
                .optional();
    }
}
