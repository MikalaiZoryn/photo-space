package com.photospace.dashboard;

import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each member's own dashboard layout in a workspace, keyed by the verified member, never by a user ID from the
 * request. A member without a saved layout gets the default for their role at version 0, and the first save
 * creates it. Owner-only widgets are left out for members, also from a layout saved while they were an owner.
 * Layout changes are preferences, not business records, so they are not audited.
 */
@Service
class DashboardLayoutService {

    private static final List<WidgetView> OWNER_DEFAULT = List.of(
            new WidgetView(DashboardWidget.AT_A_GLANCE, WidgetWidth.FULL),
            new WidgetView(DashboardWidget.UPCOMING_SESSIONS, WidgetWidth.HALF),
            new WidgetView(DashboardWidget.MY_TASKS, WidgetWidth.HALF),
            new WidgetView(DashboardWidget.OPEN_INQUIRIES, WidgetWidth.HALF),
            new WidgetView(DashboardWidget.CONTENT_TO_REVIEW, WidgetWidth.HALF),
            new WidgetView(DashboardWidget.GALLERIES, WidgetWidth.HALF),
            new WidgetView(DashboardWidget.RECENT_ACTIVITY, WidgetWidth.HALF),
            new WidgetView(DashboardWidget.QUICK_ACTIONS, WidgetWidth.HALF));

    private final JdbcClient jdbc;

    DashboardLayoutService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    LayoutView get(WorkspaceContext workspace) {
        return find(workspace, false).map(layout -> view(workspace, layout)).orElseGet(() -> defaultView(workspace));
    }

    /** Replaces the whole layout if {@code version} is still current. */
    @Transactional
    LayoutView update(WorkspaceContext workspace, int version, List<WidgetInput> widgets) {
        requireValid(workspace, widgets);
        jdbc.sql("""
                        INSERT INTO dashboard_layouts (workspace_id, user_id) VALUES (:workspaceId, :userId)
                        ON CONFLICT (workspace_id, user_id) DO NOTHING
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("userId", workspace.userId())
                .update();
        LayoutRow current = find(workspace, true).orElseThrow();
        if (current.version() != version) {
            throw new ConflictException("Your dashboard was changed in another window. Reload it and try again.");
        }
        jdbc.sql("DELETE FROM dashboard_widgets WHERE layout_id = :layoutId AND workspace_id = :workspaceId")
                .param("layoutId", current.id())
                .param("workspaceId", workspace.workspaceId())
                .update();
        for (int position = 0; position < widgets.size(); position++) {
            WidgetInput widget = widgets.get(position);
            jdbc.sql("""
                            INSERT INTO dashboard_widgets (workspace_id, layout_id, widget, position, width)
                            VALUES (:workspaceId, :layoutId, :widget, :position, :width)
                            """)
                    .param("workspaceId", workspace.workspaceId())
                    .param("layoutId", current.id())
                    .param("widget", widget.type().name())
                    .param("position", position)
                    .param("width", widget.width().name())
                    .update();
        }
        jdbc.sql("UPDATE dashboard_layouts SET version = version + 1, updated_at = now() WHERE id = :id")
                .param("id", current.id())
                .update();
        return get(workspace);
    }

    /** Forgets the saved layout, so the member sees the default again. Resetting twice changes nothing. */
    @Transactional
    LayoutView reset(WorkspaceContext workspace) {
        jdbc.sql("DELETE FROM dashboard_layouts WHERE workspace_id = :workspaceId AND user_id = :userId")
                .param("workspaceId", workspace.workspaceId())
                .param("userId", workspace.userId())
                .update();
        return defaultView(workspace);
    }

    private static void requireValid(WorkspaceContext workspace, List<WidgetInput> widgets) {
        Set<DashboardWidget> seen = EnumSet.noneOf(DashboardWidget.class);
        for (WidgetInput widget : widgets) {
            if (!seen.add(widget.type())) {
                throw InvalidFieldsException.of("widgets", "lists a widget more than once");
            }
            if (widget.type().ownerOnly() && workspace.role() != WorkspaceRole.OWNER) {
                throw InvalidFieldsException.of("widgets", "includes a widget only owners can use");
            }
        }
    }

    private LayoutView view(WorkspaceContext workspace, LayoutRow layout) {
        List<WidgetView> widgets = jdbc.sql("""
                        SELECT widget, width FROM dashboard_widgets
                         WHERE layout_id = :layoutId AND workspace_id = :workspaceId ORDER BY position""")
                .param("layoutId", layout.id())
                .param("workspaceId", workspace.workspaceId())
                .query((rs, rowNum) -> new WidgetView(
                        DashboardWidget.valueOf(rs.getString("widget")), WidgetWidth.valueOf(rs.getString("width"))))
                .list();
        return new LayoutView(visible(workspace, widgets), true, layout.version(), layout.updatedAt());
    }

    private static LayoutView defaultView(WorkspaceContext workspace) {
        return new LayoutView(visible(workspace, OWNER_DEFAULT), false, 0, null);
    }

    private static List<WidgetView> visible(WorkspaceContext workspace, List<WidgetView> widgets) {
        boolean owner = workspace.role() == WorkspaceRole.OWNER;
        return widgets.stream().filter(w -> owner || !w.type().ownerOnly()).toList();
    }

    private Optional<LayoutRow> find(WorkspaceContext workspace, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT id, version, updated_at FROM dashboard_layouts
                         WHERE workspace_id = :workspaceId AND user_id = :userId""" + (forUpdate ? " FOR UPDATE" : ""))
                .param("workspaceId", workspace.workspaceId())
                .param("userId", workspace.userId())
                .query(DashboardLayoutService::layoutRow)
                .optional();
    }

    private static LayoutRow layoutRow(ResultSet rs, int rowNum) throws SQLException {
        return new LayoutRow(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private record LayoutRow(UUID id, int version, Instant updatedAt) {}

    record WidgetView(DashboardWidget type, WidgetWidth width) {}

    /**
     * The widgets in reading order. {@code customized} is false while the member sees the default layout, which
     * has version 0 and no {@code updatedAt}.
     */
    record LayoutView(List<WidgetView> widgets, boolean customized, int version, Instant updatedAt) {}
}
