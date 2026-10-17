package com.photospace.audit;

import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.CorrelationIdFilter;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace audit log. {@link #record} runs inside the caller's transaction, so an event exists exactly when
 * its change committed. {@code detail} must be built by code from statuses, dates, and counts; never pass
 * user-entered text, tokens, or URLs.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a change made by the signed-in member of {@code workspace}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(WorkspaceContext workspace, AuditAction action, AuditTarget target, UUID targetId, String detail) {
        record(workspace.workspaceId(), workspace.userId(), action, target, targetId, detail);
    }

    /** Records a change; a null {@code actorId} means the system did it, such as an automation rule. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID workspaceId, UUID actorId, AuditAction action, AuditTarget target, UUID targetId,
            String detail) {
        jdbc.sql("""
                        INSERT INTO audit_events
                               (workspace_id, actor_id, action, target_type, target_id, detail, correlation_id)
                        VALUES (:workspaceId, :actorId, :action, :target, :targetId, :detail, :correlationId)
                        """)
                .param("workspaceId", workspaceId)
                .param("actorId", actorId)
                .param("action", action.name())
                .param("target", target.name())
                .param("targetId", targetId)
                .param("detail", detail)
                .param("correlationId", CorrelationIdFilter.current())
                .update();
    }

    /** Newest first, optionally only the events about one record. Owners only. */
    @Transactional(readOnly = true)
    PageView<AuditEventView> list(WorkspaceContext workspace, UUID targetId, PageQuery page) {
        workspace.requireOwner();
        String where = " WHERE e.workspace_id = :workspaceId" + (targetId == null ? "" : " AND e.target_id = :targetId");
        long total = jdbc.sql("SELECT count(*) FROM audit_events e" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("targetId", targetId)
                .query(Long.class)
                .single();
        List<AuditEventView> rows = jdbc.sql("""
                        SELECT e.id, e.action, e.target_type, e.target_id, e.detail, e.actor_id, u.display_name,
                               e.correlation_id, e.created_at
                          FROM audit_events e
                          LEFT JOIN users u ON u.id = e.actor_id""" + where + """
                         ORDER BY e.created_at DESC, e.id DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("targetId", targetId)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(AuditService::eventView)
                .list();
        return PageView.of(rows, page, total);
    }

    private static AuditEventView eventView(ResultSet rs, int rowNum) throws SQLException {
        UUID actorId = rs.getObject("actor_id", UUID.class);
        return new AuditEventView(
                rs.getObject("id", UUID.class),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("detail"),
                actorId == null ? null : new Actor(actorId, rs.getString("display_name")),
                rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /** Who made the change; null in {@link AuditEventView} when the system did. */
    public record Actor(UUID userId, String displayName) {}

    public record AuditEventView(
            UUID id,
            String action,
            String targetType,
            UUID targetId,
            String detail,
            Actor actor,
            String correlationId,
            Instant createdAt) {}
}
