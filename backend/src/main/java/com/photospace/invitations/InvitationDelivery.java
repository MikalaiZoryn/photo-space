package com.photospace.invitations;

import com.photospace.identity.IdentityService;
import com.photospace.identity.UserProfile;
import com.photospace.mail.EmailMessage;
import com.photospace.shared.SecretTokens;
import com.photospace.workspaces.WorkspaceService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The steps of sending one invitation email, each its own transaction, for {@link SendInvitationJob}. The token
 * is generated here, just before sending, and only its hash is written; it exists elsewhere only in the email.
 */
@Service
class InvitationDelivery {

    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);
    private static final int MAX_SUBJECT_NAME = 100;

    private final JdbcClient jdbc;
    private final WorkspaceService workspaces;
    private final IdentityService identities;
    private final InvitationProperties properties;

    InvitationDelivery(JdbcClient jdbc, WorkspaceService workspaces, IdentityService identities,
            InvitationProperties properties) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.identities = identities;
        this.properties = properties;
    }

    /**
     * Gives the invitation a new token and returns the email to send, or nothing when no email is due: the
     * invitation was revoked, accepted, or expired, or this email was already sent.
     */
    @Transactional
    Optional<Outgoing> prepare(UUID workspaceId, UUID invitationId) {
        Optional<Row> row = jdbc.sql("""
                        SELECT email, expires_at, invited_by FROM workspace_invitations
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                           AND email_status = 'QUEUED' AND expires_at > now()
                           FOR UPDATE
                        """)
                .param("id", invitationId)
                .param("workspaceId", workspaceId)
                .query((rs, i) -> new Row(rs.getString("email"), InvitationService.instant(rs, "expires_at"),
                        rs.getObject("invited_by", UUID.class)))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String token = SecretTokens.generate();
        byte[] tokenHash = SecretTokens.hash(token);
        jdbc.sql("UPDATE workspace_invitations SET token_hash = :tokenHash, updated_at = now() WHERE id = :id")
                .param("tokenHash", tokenHash)
                .param("id", invitationId)
                .update();
        String workspaceName = workspaces.name(workspaceId).orElse("a workspace");
        String inviter = identities.findProfile(row.get().invitedBy()).map(UserProfile::displayName).orElse("Someone");
        ZoneId zone = workspaces.zone(workspaceId);
        return Optional.of(new Outgoing(message(row.get().email(), workspaceName, inviter, row.get().expiresAt(), zone, token),
                tokenHash));
    }

    /** Records the send, unless the invitation was resent or changed since {@link #prepare}. */
    @Transactional
    void markSent(UUID workspaceId, UUID invitationId, byte[] tokenHash) {
        jdbc.sql("""
                        UPDATE workspace_invitations SET email_status = 'SENT', email_sent_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND email_status = 'QUEUED'
                           AND token_hash = :tokenHash
                        """)
                .param("id", invitationId)
                .param("workspaceId", workspaceId)
                .param("tokenHash", tokenHash)
                .update();
    }

    /** Records that the email could not be sent. The owner sees this and can resend. */
    @Transactional
    void markFailed(UUID workspaceId, UUID invitationId) {
        jdbc.sql("""
                        UPDATE workspace_invitations SET email_status = 'FAILED', token_hash = NULL, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND email_status = 'QUEUED'
                        """)
                .param("id", invitationId)
                .param("workspaceId", workspaceId)
                .update();
    }

    private EmailMessage message(String to, String workspaceName, String inviter, Instant expiresAt, ZoneId zone,
            String token) {
        String name = EmailMessage.singleLine(workspaceName).strip();
        String subjectName = name.length() > MAX_SUBJECT_NAME ? name.substring(0, MAX_SUBJECT_NAME) + "…" : name;
        String link = properties.appUrl().toString().replaceAll("/+$", "") + "/#/invite/" + token;
        String body = """
                %s invited you to join the workspace "%s" on Photo Space.

                To accept, open this link and sign in with the Google account for %s:

                %s

                The link works until %s (%s). If you did not expect this invitation, you can ignore this email.
                """.formatted(EmailMessage.singleLine(inviter).strip(), name, to, link,
                EXPIRY.format(expiresAt.atZone(zone)), zone.getId());
        return new EmailMessage(to, "You're invited to join " + subjectName + " on Photo Space", body);
    }

    private record Row(String email, Instant expiresAt, UUID invitedBy) {}

    /** The email to send and the hash of the token in it. The message holds a credential: never log it. */
    record Outgoing(EmailMessage message, byte[] tokenHash) {}
}
