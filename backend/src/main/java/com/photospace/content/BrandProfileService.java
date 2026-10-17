package com.photospace.content;

import com.photospace.shared.web.ConflictException;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workspace's brand profile. A workspace without a saved profile reads as an empty one at version 0, and the
 * first save creates it.
 */
@Service
class BrandProfileService {

    private final JdbcClient jdbc;

    BrandProfileService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    BrandProfileView get(WorkspaceContext workspace) {
        return find(workspace.workspaceId(), false).orElse(BrandProfileView.EMPTY);
    }

    @Transactional
    BrandProfileView update(WorkspaceContext workspace, int version, BrandProfileInput input) {
        jdbc.sql("INSERT INTO brand_profiles (workspace_id) VALUES (:workspaceId) ON CONFLICT (workspace_id) DO NOTHING")
                .param("workspaceId", workspace.workspaceId())
                .update();
        BrandProfileView current = find(workspace.workspaceId(), true).orElseThrow();
        if (current.version() != version) {
            throw new ConflictException("The brand profile was changed by someone else. Reload it and try again.");
        }
        jdbc.sql("""
                        UPDATE brand_profiles
                           SET voice = :voice, audience = :audience, preferences = :preferences, avoid = :avoid,
                               version = version + 1, updated_at = now()
                         WHERE workspace_id = :workspaceId
                        """)
                .param("voice", input.voice())
                .param("audience", input.audience())
                .param("preferences", input.preferences())
                .param("avoid", input.avoid())
                .param("workspaceId", workspace.workspaceId())
                .update();
        return find(workspace.workspaceId(), false).orElseThrow();
    }

    /** The profile as the generation job sends it. Runs without a signed-in user, scoped by the job's workspace. */
    GenerationRequest.Brand forGeneration(UUID workspaceId) {
        BrandProfileView profile = find(workspaceId, false).orElse(BrandProfileView.EMPTY);
        return new GenerationRequest.Brand(profile.voice(), profile.audience(), profile.preferences(), profile.avoid());
    }

    private Optional<BrandProfileView> find(UUID workspaceId, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT voice, audience, preferences, avoid, version, updated_at
                          FROM brand_profiles WHERE workspace_id = :workspaceId""" + (forUpdate ? " FOR UPDATE" : ""))
                .param("workspaceId", workspaceId)
                .query(BrandProfileService::view)
                .optional();
    }

    private static BrandProfileView view(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        return new BrandProfileView(
                rs.getString("voice"),
                rs.getString("audience"),
                rs.getString("preferences"),
                rs.getString("avoid"),
                rs.getInt("version"),
                updatedAt.toInstant());
    }

    /** {@code updatedAt} is null until the profile is first saved. */
    record BrandProfileView(
            String voice, String audience, String preferences, String avoid, int version, Instant updatedAt) {

        static final BrandProfileView EMPTY = new BrandProfileView(null, null, null, null, 0, null);
    }
}
