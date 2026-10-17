package com.photospace.invitations;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.identity.IdentityService;
import com.photospace.identity.UserProfile;
import com.photospace.invitations.InvitationService.InvitationState;
import com.photospace.shared.SecretTokens;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceRole;
import com.photospace.workspaces.WorkspaceService;
import com.photospace.workspaces.WorkspaceService.WorkspaceView;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations as the invited person sees them, authorized by the token from the email. Accepting needs a
 * signed-in user with a verified email equal to the invited address; the membership is then created for that
 * user, so it follows their Google {@code sub}, not the address. Unknown and malformed tokens get the same 404.
 */
@Service
class InvitationAcceptance {

    private final JdbcClient jdbc;
    private final WorkspaceService workspaces;
    private final IdentityService identities;
    private final AuditService audit;

    InvitationAcceptance(JdbcClient jdbc, WorkspaceService workspaces, IdentityService identities, AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.identities = identities;
        this.audit = audit;
    }

    /** What the invitation is for, and whether the signed-in user can accept it. */
    @Transactional(readOnly = true)
    InvitationPreview preview(UUID userId, String token) {
        Row row = find(token, false);
        return new InvitationPreview(
                workspaces.name(row.workspaceId()).orElseThrow(NotFoundException::new),
                identities.findProfile(row.invitedBy()).map(UserProfile::displayName).orElse(null),
                row.email(),
                row.state(),
                row.expiresAt(),
                identities.hasVerifiedEmail(userId, row.email()),
                workspaces.findRole(row.workspaceId(), userId).isPresent());
    }

    /**
     * Joins the workspace and returns it. Accepting again as the same user returns the workspace again; anyone
     * else is refused once the invitation is used.
     */
    @Transactional
    WorkspaceView accept(UUID userId, String token) {
        Row row = find(token, true);
        switch (row.state()) {
            case ACCEPTED -> {
                // A repeated accept by the same user, who still belongs to the workspace, changes nothing.
                if (userId.equals(row.acceptedBy()) && workspaces.findRole(row.workspaceId(), userId).isPresent()) {
                    return workspaces.join(row.workspaceId(), userId, row.role());
                }
                throw new ConflictException("This invitation was already used.");
            }
            case REVOKED -> throw new ConflictException("This invitation was withdrawn. Ask the workspace owner for a new one.");
            case EXPIRED -> throw new ConflictException("This invitation has expired. Ask the workspace owner to send it again.");
            case PENDING -> {}
        }
        if (!identities.hasVerifiedEmail(userId, row.email())) {
            throw new ConflictException(
                    "This invitation is for a different email address. Sign in with the account it was sent to.");
        }
        WorkspaceView workspace = workspaces.join(row.workspaceId(), userId, row.role());
        jdbc.sql("""
                        UPDATE workspace_invitations
                           SET status = 'ACCEPTED', accepted_by = :userId, accepted_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'PENDING'
                        """)
                .param("userId", userId)
                .param("id", row.id())
                .update();
        audit.record(row.workspaceId(), userId, AuditAction.INVITATION_ACCEPTED, AuditTarget.INVITATION, row.id(), null);
        return workspace;
    }

    private Row find(String token, boolean forUpdate) {
        if (!SecretTokens.isWellFormed(token)) {
            throw new NotFoundException();
        }
        return jdbc.sql("""
                        SELECT id, workspace_id, email, role, invited_by, accepted_by, expires_at,
                               CASE WHEN status = 'PENDING' AND expires_at <= now() THEN 'EXPIRED' ELSE status END AS state
                          FROM workspace_invitations
                         WHERE token_hash = :tokenHash
                        """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("tokenHash", SecretTokens.hash(token))
                .query((rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("email"),
                        WorkspaceRole.valueOf(rs.getString("role")),
                        rs.getObject("invited_by", UUID.class),
                        rs.getObject("accepted_by", UUID.class),
                        InvitationService.instant(rs, "expires_at"),
                        InvitationState.valueOf(rs.getString("state"))))
                .optional()
                .orElseThrow(NotFoundException::new);
    }

    private record Row(UUID id, UUID workspaceId, String email, WorkspaceRole role, UUID invitedBy, UUID acceptedBy,
            Instant expiresAt, InvitationState state) {}

    /**
     * The invitation for the person holding its link. {@code emailMatches} says whether the signed-in user can
     * accept it; {@code alreadyMember} whether they belong to the workspace already.
     */
    record InvitationPreview(
            String workspaceName,
            String invitedBy,
            String email,
            InvitationState state,
            Instant expiresAt,
            boolean emailMatches,
            boolean alreadyMember) {}
}
