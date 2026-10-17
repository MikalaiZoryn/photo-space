package com.photospace.invitations;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.jobs.JobService;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations as a workspace's owners manage them: invite an email address, resend, and revoke. Each send is a
 * background job ({@link SendInvitationJob}), queued in the same transaction as the change that asks for it.
 * Revoked and accepted invitations are kept; the list shows only open ones.
 */
@Service
class InvitationService {

    static final String COLUMNS = """
            i.id, i.email, i.role, i.email_status, i.expires_at, i.email_sent_at, i.created_at,
            CASE WHEN i.status = 'PENDING' AND i.expires_at <= now() THEN 'EXPIRED' ELSE i.status END AS state""";

    private final JdbcClient jdbc;
    private final JobService jobs;
    private final AuditService audit;
    private final InvitationProperties properties;

    InvitationService(JdbcClient jdbc, JobService jobs, AuditService audit, InvitationProperties properties) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.audit = audit;
        this.properties = properties;
    }

    /** Open invitations, newest first. A workspace has at most {@code maxPending}, so this is not paged. */
    @Transactional(readOnly = true)
    List<InvitationView> listPending(WorkspaceContext workspace) {
        workspace.requireOwner();
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workspace_invitations i
                        WHERE i.workspace_id = :workspaceId AND i.status = 'PENDING'
                        ORDER BY i.created_at DESC, i.id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .query(InvitationService::invitationView)
                .list();
    }

    /** Invites {@code email} to join as a member and queues the email. */
    @Transactional
    InvitationView create(WorkspaceContext workspace, String email) {
        workspace.requireOwner();
        // Serializes invitations per workspace, so concurrent requests cannot exceed the limit.
        jdbc.sql("SELECT id FROM workspaces WHERE id = :workspaceId FOR UPDATE")
                .param("workspaceId", workspace.workspaceId())
                .query(UUID.class)
                .optional()
                .orElseThrow(NotFoundException::new);
        long pending = jdbc.sql("SELECT count(*) FROM workspace_invitations WHERE workspace_id = :workspaceId AND status = 'PENDING'")
                .param("workspaceId", workspace.workspaceId())
                .query(Long.class)
                .single();
        if (pending >= properties.maxPending()) {
            throw new ConflictException("This workspace has too many open invitations. Revoke some before inviting more people.");
        }
        Optional<UUID> id = jdbc.sql("""
                        INSERT INTO workspace_invitations
                               (workspace_id, email, role, status, expires_at, email_status, invited_by)
                        VALUES (:workspaceId, :email, :role, 'PENDING', now() + make_interval(secs => :validSeconds),
                                'QUEUED', :userId)
                        ON CONFLICT (workspace_id, email) WHERE status = 'PENDING' DO NOTHING
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("email", normalize(email))
                .param("role", WorkspaceRole.MEMBER.name())
                .param("validSeconds", properties.validFor().toSeconds())
                .param("userId", workspace.userId())
                .query(UUID.class)
                .optional();
        if (id.isEmpty()) {
            throw new ConflictException("This address already has an open invitation. Resend it instead.");
        }
        queueEmail(workspace.workspaceId(), id.get(), 1);
        audit.record(workspace, AuditAction.INVITATION_CREATED, AuditTarget.INVITATION, id.get(), null);
        return get(workspace, id.get());
    }

    /**
     * Sends the invitation again with a new link, which works for a new validity period. The previous link stops
     * working at once. Expired invitations can be resent; revoked and accepted ones cannot.
     */
    @Transactional
    InvitationView resend(WorkspaceContext workspace, UUID invitationId) {
        workspace.requireOwner();
        Optional<Integer> sendCount = jdbc.sql("""
                        UPDATE workspace_invitations
                           SET send_count = send_count + 1, email_status = 'QUEUED', email_requested_at = now(),
                               token_hash = NULL, expires_at = now() + make_interval(secs => :validSeconds),
                               updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                           AND email_requested_at <= now() - make_interval(secs => :cooldownSeconds)
                        RETURNING send_count
                        """)
                .param("validSeconds", properties.validFor().toSeconds())
                .param("cooldownSeconds", properties.resendCooldown().toSeconds())
                .param("id", invitationId)
                .param("workspaceId", workspace.workspaceId())
                .query(Integer.class)
                .optional();
        if (sendCount.isEmpty()) {
            InvitationView current = get(workspace, invitationId);
            if (current.state() == InvitationState.ACCEPTED || current.state() == InvitationState.REVOKED) {
                throw new ConflictException("Only open invitations can be resent.");
            }
            throw new ConflictException("This invitation was just sent. Wait a minute before sending it again.");
        }
        queueEmail(workspace.workspaceId(), invitationId, sendCount.get());
        audit.record(workspace, AuditAction.INVITATION_RESENT, AuditTarget.INVITATION, invitationId,
                "send " + sendCount.get());
        return get(workspace, invitationId);
    }

    /** Stops the invitation's link from working, for good. Revoking a revoked invitation changes nothing. */
    @Transactional
    InvitationView revoke(WorkspaceContext workspace, UUID invitationId) {
        workspace.requireOwner();
        int revoked = jdbc.sql("""
                        UPDATE workspace_invitations
                           SET status = 'REVOKED', revoked_by = :userId, revoked_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                        """)
                .param("userId", workspace.userId())
                .param("id", invitationId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        if (revoked == 1) {
            audit.record(workspace, AuditAction.INVITATION_REVOKED, AuditTarget.INVITATION, invitationId, null);
        }
        InvitationView current = get(workspace, invitationId);
        if (current.state() == InvitationState.ACCEPTED) {
            throw new ConflictException("This invitation was already accepted.");
        }
        return current;
    }

    private void queueEmail(UUID workspaceId, UUID invitationId, int sendCount) {
        jobs.enqueue(workspaceId, SendInvitationJob.TYPE, invitationId,
                SendInvitationJob.TYPE + ":" + invitationId + ":" + sendCount);
    }

    private InvitationView get(WorkspaceContext workspace, UUID invitationId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM workspace_invitations i
                        WHERE i.id = :id AND i.workspace_id = :workspaceId
                        """)
                .param("id", invitationId)
                .param("workspaceId", workspace.workspaceId())
                .query(InvitationService::invitationView)
                .optional()
                .orElseThrow(NotFoundException::new);
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static InvitationView invitationView(ResultSet rs, int rowNum) throws SQLException {
        return new InvitationView(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                WorkspaceRole.valueOf(rs.getString("role")),
                InvitationState.valueOf(rs.getString("state")),
                EmailStatus.valueOf(rs.getString("email_status")),
                instant(rs, "expires_at"),
                instant(rs, "email_sent_at"),
                instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** {@code EXPIRED} is a {@code PENDING} invitation past its expiry time; resending opens it again. */
    enum InvitationState {
        PENDING,
        EXPIRED,
        ACCEPTED,
        REVOKED
    }

    /** Where the newest email is: waiting for the worker, handed to the provider, or given up on. */
    enum EmailStatus {
        QUEUED,
        SENT,
        FAILED
    }

    /** An invitation as owners see it. It never includes the token, which is not stored. */
    record InvitationView(
            UUID id,
            String email,
            WorkspaceRole role,
            InvitationState state,
            EmailStatus emailStatus,
            Instant expiresAt,
            Instant emailSentAt,
            Instant createdAt) {}
}
