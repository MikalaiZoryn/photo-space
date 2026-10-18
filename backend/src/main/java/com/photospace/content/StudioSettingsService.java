package com.photospace.content;

import com.photospace.shared.web.ConflictException;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The workspace's Content Studio settings, with optimistic locking like the brand profile. */
@Service
class StudioSettingsService {

    private final JdbcClient jdbc;

    StudioSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    StudioSettings get(WorkspaceContext workspace) {
        return forWorkspace(workspace.workspaceId());
    }

    @Transactional
    StudioSettings update(WorkspaceContext workspace, int version, StudioSettingsInput input) {
        jdbc.sql("""
                        INSERT INTO content_studio_settings (workspace_id) VALUES (:workspaceId)
                        ON CONFLICT (workspace_id) DO NOTHING""")
                .param("workspaceId", workspace.workspaceId())
                .update();
        StudioSettings current = find(workspace.workspaceId(), true).orElseThrow();
        if (current.version() != version) {
            throw new ConflictException("The Content Studio settings were changed by someone else. Reload them and try again.");
        }
        jdbc.sql("""
                        UPDATE content_studio_settings
                           SET weekly_goal = :weeklyGoal, auto_plans = :autoPlans, sneak_peek_days = :sneakPeekDays,
                               drip_posts = :dripPosts, drip_spacing_days = :dripSpacingDays, studio_area = :studioArea,
                               version = version + 1, updated_at = now()
                         WHERE workspace_id = :workspaceId
                        """)
                .param("weeklyGoal", input.weeklyGoal())
                .param("autoPlans", input.autoPlans())
                .param("sneakPeekDays", input.sneakPeekDays())
                .param("dripPosts", input.dripPosts())
                .param("dripSpacingDays", input.dripSpacingDays())
                .param("studioArea", input.studioArea())
                .param("workspaceId", workspace.workspaceId())
                .update();
        return find(workspace.workspaceId(), false).orElseThrow();
    }

    /** The settings of a workspace, for jobs and checks that run without a signed-in user. */
    StudioSettings forWorkspace(UUID workspaceId) {
        return find(workspaceId, false).orElse(StudioSettings.DEFAULTS);
    }

    private Optional<StudioSettings> find(UUID workspaceId, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT weekly_goal, auto_plans, sneak_peek_days, drip_posts, drip_spacing_days, studio_area,
                               version, updated_at
                          FROM content_studio_settings WHERE workspace_id = :workspaceId"""
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("workspaceId", workspaceId)
                .query(StudioSettingsService::settings)
                .optional();
    }

    private static StudioSettings settings(ResultSet rs, int rowNum) throws SQLException {
        return new StudioSettings(
                rs.getInt("weekly_goal"),
                rs.getBoolean("auto_plans"),
                rs.getInt("sneak_peek_days"),
                rs.getInt("drip_posts"),
                rs.getInt("drip_spacing_days"),
                rs.getString("studio_area"),
                rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
