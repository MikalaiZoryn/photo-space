package com.photospace.workspaces;

import com.photospace.shared.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspaces and memberships. Methods that act on one workspace take a {@link WorkspaceContext}, which proves
 * membership was already checked, and scope their SQL by its workspace ID.
 */
@Service
public class WorkspaceService {

    private static final String WORKSPACE_COLUMNS =
            "w.id, w.name, w.timezone, w.created_at, m.role";

    private final JdbcClient jdbc;

    public WorkspaceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a workspace with the creator as its owner. */
    @Transactional
    public WorkspaceView create(UUID userId, String name, String timezone) {
        UUID workspaceId = jdbc.sql("INSERT INTO workspaces (name, timezone) VALUES (:name, :timezone) RETURNING id")
                .param("name", name.strip())
                .param("timezone", timezone)
                .query(UUID.class)
                .single();
        jdbc.sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (:workspaceId, :userId, :role)")
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .param("role", WorkspaceRole.OWNER.name())
                .update();
        return get(new WorkspaceContext(workspaceId, userId, WorkspaceRole.OWNER));
    }

    /** The workspaces the user belongs to, by name. A user belongs to a handful at most, so this is not paged. */
    @Transactional(readOnly = true)
    public List<WorkspaceView> listForUser(UUID userId) {
        return jdbc.sql("SELECT " + WORKSPACE_COLUMNS + """
                         FROM workspaces w
                         JOIN workspace_members m ON m.workspace_id = w.id
                        WHERE m.user_id = :userId
                        ORDER BY lower(w.name), w.id
                        """)
                .param("userId", userId)
                .query(WorkspaceService::workspaceView)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceRole> findRole(UUID workspaceId, UUID userId) {
        return jdbc.sql("SELECT role FROM workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId")
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query((rs, i) -> WorkspaceRole.valueOf(rs.getString("role")))
                .optional();
    }

    @Transactional(readOnly = true)
    public WorkspaceView get(WorkspaceContext workspace) {
        return jdbc.sql("SELECT " + WORKSPACE_COLUMNS + """
                         FROM workspaces w
                         JOIN workspace_members m ON m.workspace_id = w.id
                        WHERE w.id = :workspaceId AND m.user_id = :userId
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("userId", workspace.userId())
                .query(WorkspaceService::workspaceView)
                .optional()
                .orElseThrow(NotFoundException::new);
    }

    /** The workspace's time zone, in which session times are entered and shown. */
    @Transactional(readOnly = true)
    public ZoneId zone(WorkspaceContext workspace) {
        return jdbc.sql("SELECT timezone FROM workspaces WHERE id = :workspaceId")
                .param("workspaceId", workspace.workspaceId())
                .query(String.class)
                .optional()
                .map(ZoneId::of)
                .orElseThrow(NotFoundException::new);
    }

    /** Renames the workspace or changes its time zone. Owners only. */
    @Transactional
    public WorkspaceView update(WorkspaceContext workspace, String name, String timezone) {
        workspace.requireOwner();
        int updated = jdbc.sql("""
                        UPDATE workspaces SET name = :name, timezone = :timezone, updated_at = now()
                         WHERE id = :workspaceId
                        """)
                .param("name", name.strip())
                .param("timezone", timezone)
                .param("workspaceId", workspace.workspaceId())
                .update();
        if (updated == 0) {
            throw new NotFoundException();
        }
        return get(workspace);
    }

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(WorkspaceContext workspace) {
        return jdbc.sql("""
                        SELECT m.user_id, u.display_name, m.role, m.created_at
                          FROM workspace_members m
                          JOIN users u ON u.id = m.user_id
                         WHERE m.workspace_id = :workspaceId
                         ORDER BY m.created_at, m.user_id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .query((rs, i) -> new MemberView(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        WorkspaceRole.valueOf(rs.getString("role")),
                        instant(rs, "created_at")))
                .list();
    }

    private static WorkspaceView workspaceView(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("timezone"),
                WorkspaceRole.valueOf(rs.getString("role")),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** A workspace as seen by one member: includes that member's role. */
    public record WorkspaceView(UUID id, String name, String timezone, WorkspaceRole role, Instant createdAt) {}

    public record MemberView(UUID userId, String displayName, WorkspaceRole role, Instant joinedAt) {}
}
