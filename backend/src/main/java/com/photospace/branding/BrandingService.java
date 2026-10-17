package com.photospace.branding;

import com.photospace.assets.AssetService;
import com.photospace.assets.AssetService.StandaloneImage;
import com.photospace.assets.AssetStatus;
import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What visitors of a workspace's public inquiry pages see of the business: a display name, a logo, two colors,
 * a font, and optional contact details. A workspace without saved branding reads as the defaults at version 0,
 * and the first save creates it. Changes are audited. This is not the AI brand profile (content module).
 */
@Service
public class BrandingService {

    static final String DEFAULT_PRIMARY = "#18181b";
    static final String DEFAULT_ACCENT = "#7c3aed";
    static final BrandFont DEFAULT_FONT = BrandFont.INTER;

    private static final String COLUMNS = """
            display_name, logo_asset_id, primary_color, accent_color, font, contact_email, contact_phone,
            description, version, updated_at""";

    private final JdbcClient jdbc;
    private final WorkspaceService workspaces;
    private final AssetService assets;
    private final AuditService audit;

    BrandingService(JdbcClient jdbc, WorkspaceService workspaces, AssetService assets, AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.assets = assets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    BrandingView get(WorkspaceContext workspace) {
        BrandingRow row = find(workspace.workspaceId(), false).orElse(BrandingRow.DEFAULTS);
        return new BrandingView(
                row.displayName(),
                workspaces.name(workspace.workspaceId()).orElseThrow(),
                logo(workspace.workspaceId(), row.logoAssetId()),
                row.primaryColor(),
                row.accentColor(),
                row.font(),
                row.email(),
                row.phone(),
                row.description(),
                row.version(),
                row.updatedAt());
    }

    /** Saves new branding if {@code version} is still current. A logo must be a processed logo of this workspace. */
    @Transactional
    BrandingView update(WorkspaceContext workspace, int version, BrandingInput input) {
        if (input.logoAssetId() != null && assets.findStandaloneImage(workspace.workspaceId(), input.logoAssetId())
                .filter(image -> image.status() == AssetStatus.READY)
                .isEmpty()) {
            throw InvalidFieldsException.of("logoAssetId", "must be a processed logo of this workspace");
        }
        Optional<BrandingRow> current = find(workspace.workspaceId(), true);
        int currentVersion = current.map(BrandingRow::version).orElse(0);
        if (currentVersion != version) {
            throw conflict();
        }
        String sql = current.isEmpty()
                ? """
                  INSERT INTO workspace_branding (workspace_id, display_name, logo_asset_id, primary_color, accent_color,
                                                  font, contact_email, contact_phone, description, version)
                  VALUES (:workspaceId, :displayName, :logoAssetId, :primaryColor, :accentColor, :font, :email, :phone,
                          :description, 1)
                  """
                : """
                  UPDATE workspace_branding
                     SET display_name = :displayName, logo_asset_id = :logoAssetId, primary_color = :primaryColor,
                         accent_color = :accentColor, font = :font, contact_email = :email, contact_phone = :phone,
                         description = :description, version = version + 1, updated_at = now()
                   WHERE workspace_id = :workspaceId
                  """;
        try {
            jdbc.sql(sql)
                    .param("workspaceId", workspace.workspaceId())
                    .param("displayName", input.displayName())
                    .param("logoAssetId", input.logoAssetId())
                    .param("primaryColor", input.primaryColor())
                    .param("accentColor", input.accentColor())
                    .param("font", input.font().name())
                    .param("email", input.email())
                    .param("phone", input.phone())
                    .param("description", input.description())
                    .update();
        } catch (DuplicateKeyException e) {
            // Someone else saved the first version at the same moment.
            throw conflict();
        }
        audit.record(workspace, AuditAction.BRANDING_UPDATED, AuditTarget.BRANDING, workspace.workspaceId(),
                "version " + (version + 1) + (input.logoAssetId() == null ? ", no logo" : ", with logo"));
        return get(workspace);
    }

    /**
     * The branding a visitor of the workspace's public pages sees, with defaults filled in. For callers that
     * authorized access some other way than membership, such as a published form's public slug.
     */
    @Transactional(readOnly = true)
    public PublicBranding forVisitors(UUID workspaceId) {
        BrandingRow row = find(workspaceId, false).orElse(BrandingRow.DEFAULTS);
        StandaloneImage logo = logo(workspaceId, row.logoAssetId());
        return new PublicBranding(
                row.displayName() != null ? row.displayName() : workspaces.name(workspaceId).orElseThrow(),
                logo == null ? null : logo.url(),
                row.primaryColor(),
                row.accentColor(),
                row.font(),
                row.email(),
                row.phone(),
                row.description());
    }

    private StandaloneImage logo(UUID workspaceId, UUID assetId) {
        return assetId == null ? null : assets.findStandaloneImage(workspaceId, assetId).orElse(null);
    }

    private static ConflictException conflict() {
        return new ConflictException("The branding was changed by someone else. Reload it and try again.");
    }

    private Optional<BrandingRow> find(UUID workspaceId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM workspace_branding WHERE workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("workspaceId", workspaceId)
                .query(BrandingService::brandingRow)
                .optional();
    }

    private static BrandingRow brandingRow(ResultSet rs, int rowNum) throws SQLException {
        return new BrandingRow(
                rs.getString("display_name"),
                rs.getObject("logo_asset_id", UUID.class),
                rs.getString("primary_color"),
                rs.getString("accent_color"),
                BrandFont.valueOf(rs.getString("font")),
                rs.getString("contact_email"),
                rs.getString("contact_phone"),
                rs.getString("description"),
                rs.getInt("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private record BrandingRow(
            String displayName,
            UUID logoAssetId,
            String primaryColor,
            String accentColor,
            BrandFont font,
            String email,
            String phone,
            String description,
            int version,
            Instant updatedAt) {

        static final BrandingRow DEFAULTS =
                new BrandingRow(null, null, DEFAULT_PRIMARY, DEFAULT_ACCENT, DEFAULT_FONT, null, null, null, 0, null);
    }

    /**
     * Branding as members edit it. {@code displayName} is null when visitors see {@code workspaceName} instead.
     * {@code logo} carries the logo's processing status and, once ready, a URL that expires after a few minutes.
     */
    public record BrandingView(
            String displayName,
            String workspaceName,
            StandaloneImage logo,
            String primaryColor,
            String accentColor,
            BrandFont font,
            String email,
            String phone,
            String description,
            int version,
            Instant updatedAt) {}

    /** Branding as visitors see it: no IDs, and a logo URL only once the logo is ready. */
    public record PublicBranding(
            String displayName,
            URI logoUrl,
            String primaryColor,
            String accentColor,
            BrandFont font,
            String email,
            String phone,
            String description) {}
}
