package com.photospace.galleries;

import com.photospace.assets.AssetService;
import com.photospace.assets.AssetService.AssetView;
import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace's galleries: settings, photos and their order, publication, and archiving. Every query is scoped
 * by the verified workspace. A gallery holds ready photos of its own session only; the database enforces that
 * too (V6). Galleries never delete photos.
 */
@Service
class GalleryService {

    private static final String COLUMNS = """
            g.id, g.workspace_id, g.session_id, g.title, g.status, g.downloads, g.archived_at, g.version,
            g.created_at, g.updated_at,
            (SELECT count(*) FROM gallery_assets ga WHERE ga.gallery_id = g.id) AS photo_count""";

    private final JdbcClient jdbc;
    private final PhotoSessionService sessions;
    private final AssetService assets;
    private final GalleryProperties properties;
    private final AuditService audit;

    GalleryService(JdbcClient jdbc, PhotoSessionService sessions, AssetService assets, GalleryProperties properties,
            AuditService audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.assets = assets;
        this.properties = properties;
        this.audit = audit;
    }

    /** Creates an empty draft gallery for a session that is not archived. */
    @Transactional
    GalleryDetail create(WorkspaceContext workspace, UUID sessionId, GalleryInput input) {
        SessionSummary session = sessions.findSummary(workspace, sessionId)
                .orElseThrow(() -> InvalidFieldsException.of("sessionId", "does not match a session in this workspace"));
        if (session.archived()) {
            throw new ConflictException("This session is archived. Restore it before creating a gallery.");
        }
        UUID id = jdbc.sql("""
                        INSERT INTO galleries (workspace_id, session_id, title, status, downloads)
                        VALUES (:workspaceId, :sessionId, :title, 'DRAFT', :downloads)
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .param("title", input.title())
                .param("downloads", input.downloads().name())
                .query(UUID.class)
                .single();
        return get(workspace, id);
    }

    /** The gallery with its photos in display order. */
    @Transactional(readOnly = true)
    GalleryDetail get(WorkspaceContext workspace, UUID galleryId) {
        GalleryRow gallery = find(workspace.workspaceId(), galleryId, false).orElseThrow(NotFoundException::new);
        return new GalleryDetail(view(gallery), photos(gallery));
    }

    /** Active or archived galleries, optionally of one session, newest first. */
    @Transactional(readOnly = true)
    PageView<GalleryView> list(WorkspaceContext workspace, UUID sessionId, boolean archived, PageQuery page) {
        String where = " WHERE g.workspace_id = :workspaceId AND g.archived_at IS " + (archived ? "NOT NULL" : "NULL")
                + (sessionId == null ? "" : " AND g.session_id = :sessionId");
        long total = jdbc.sql("SELECT count(*) FROM galleries g" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
        List<GalleryRow> rows = jdbc.sql("SELECT " + COLUMNS + " FROM galleries g" + where
                        + " ORDER BY g.created_at DESC, g.id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(GalleryService::galleryRow)
                .list();
        return PageView.of(rows, page, total).map(GalleryService::view);
    }

    @Transactional
    GalleryDetail update(WorkspaceContext workspace, UUID galleryId, int version, GalleryInput input) {
        lockForEdit(workspace, galleryId, version);
        jdbc.sql("""
                        UPDATE galleries SET title = :title, downloads = :downloads, version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("title", input.title())
                .param("downloads", input.downloads().name())
                .param("id", galleryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return get(workspace, galleryId);
    }

    /**
     * Replaces the gallery's photos with {@code assetIds}, in that order. Every ID must be a ready photo of the
     * gallery's session. A published gallery cannot be emptied.
     */
    @Transactional
    GalleryDetail setPhotos(WorkspaceContext workspace, UUID galleryId, int version, List<UUID> assetIds) {
        GalleryRow gallery = lockForEdit(workspace, galleryId, version);
        LinkedHashSet<UUID> ordered = new LinkedHashSet<>(assetIds);
        if (ordered.size() != assetIds.size()) {
            throw InvalidFieldsException.of("assetIds", "must not contain the same photo twice");
        }
        if (ordered.size() > properties.maxPhotos()) {
            throw InvalidFieldsException.of("assetIds", "must have at most " + properties.maxPhotos() + " photos");
        }
        if (assets.findReady(workspace.workspaceId(), gallery.sessionId(), ordered).size() != ordered.size()) {
            throw InvalidFieldsException.of("assetIds", "must be ready photos of this gallery's session");
        }
        if (ordered.isEmpty() && gallery.status() == GalleryStatus.PUBLISHED) {
            throw new ConflictException("A published gallery needs at least one photo. Unpublish it first.");
        }
        jdbc.sql("DELETE FROM gallery_assets WHERE gallery_id = :id AND workspace_id = :workspaceId")
                .param("id", galleryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        if (!ordered.isEmpty()) {
            jdbc.sql("""
                            INSERT INTO gallery_assets (gallery_id, asset_id, workspace_id, session_id, position)
                            SELECT :id, a.asset_id, :workspaceId, :sessionId, a.ordinal - 1
                              FROM unnest(CAST(:assetIds AS uuid[])) WITH ORDINALITY AS a (asset_id, ordinal)
                            """)
                    .param("id", galleryId)
                    .param("workspaceId", workspace.workspaceId())
                    .param("sessionId", gallery.sessionId())
                    .param("assetIds", ordered.toArray(UUID[]::new))
                    .update();
        }
        bumpVersion(workspace, galleryId);
        return get(workspace, galleryId);
    }

    /**
     * Publishes the gallery, so its share links open it, or takes it back to draft, so they stop working without
     * being revoked. Asking for the current status changes nothing.
     */
    @Transactional
    GalleryDetail changeStatus(WorkspaceContext workspace, UUID galleryId, GalleryStatus target) {
        GalleryRow gallery = find(workspace.workspaceId(), galleryId, true).orElseThrow(NotFoundException::new);
        requireEditable(gallery);
        if (gallery.status() == target) {
            return get(workspace, galleryId);
        }
        if (target == GalleryStatus.PUBLISHED && gallery.photoCount() == 0) {
            throw new ConflictException("Add photos before publishing the gallery.");
        }
        jdbc.sql("""
                        UPDATE galleries SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", target.name())
                .param("id", galleryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace,
                target == GalleryStatus.PUBLISHED ? AuditAction.GALLERY_PUBLISHED : AuditAction.GALLERY_UNPUBLISHED,
                AuditTarget.GALLERY, galleryId, null);
        return get(workspace, galleryId);
    }

    /** Hides the gallery from default lists and closes it to visitors. Archiving an archived gallery changes nothing. */
    @Transactional
    GalleryDetail archive(WorkspaceContext workspace, UUID galleryId) {
        return setArchived(workspace, galleryId, true);
    }

    @Transactional
    GalleryDetail restore(WorkspaceContext workspace, UUID galleryId) {
        return setArchived(workspace, galleryId, false);
    }

    /** A gallery of this workspace, archived or not. Empty for unknown IDs and other workspaces' galleries alike. */
    Optional<GalleryRow> find(UUID workspaceId, UUID galleryId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM galleries g WHERE g.id = :id AND g.workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE OF g" : ""))
                .param("id", galleryId)
                .param("workspaceId", workspaceId)
                .query(GalleryService::galleryRow)
                .optional();
    }

    /** The gallery's ready photos in display order. */
    List<AssetView> photos(GalleryRow gallery) {
        List<UUID> ids = jdbc.sql("SELECT asset_id FROM gallery_assets WHERE gallery_id = :id AND workspace_id = :workspaceId"
                        + " ORDER BY position")
                .param("id", gallery.id())
                .param("workspaceId", gallery.workspaceId())
                .query(UUID.class)
                .list();
        Map<UUID, AssetView> ready = assets.findReady(gallery.workspaceId(), gallery.sessionId(), ids);
        return ids.stream().map(ready::get).filter(Objects::nonNull).toList();
    }

    private GalleryRow lockForEdit(WorkspaceContext workspace, UUID galleryId, int version) {
        GalleryRow gallery = find(workspace.workspaceId(), galleryId, true).orElseThrow(NotFoundException::new);
        requireEditable(gallery);
        if (gallery.version() != version) {
            throw new ConflictException("This gallery was changed by someone else. Reload it and try again.");
        }
        return gallery;
    }

    private GalleryDetail setArchived(WorkspaceContext workspace, UUID galleryId, boolean archived) {
        jdbc.sql("""
                        UPDATE galleries
                           SET archived_at = CASE WHEN :archived THEN now() END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND (archived_at IS NOT NULL) <> :archived
                        """)
                .param("archived", archived)
                .param("id", galleryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return get(workspace, galleryId);
    }

    private void bumpVersion(WorkspaceContext workspace, UUID galleryId) {
        jdbc.sql("UPDATE galleries SET version = version + 1, updated_at = now() WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", galleryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
    }

    static void requireEditable(GalleryRow gallery) {
        if (gallery.archivedAt() != null) {
            throw new ConflictException("This gallery is archived. Restore it before changing it.");
        }
    }

    private static GalleryView view(GalleryRow row) {
        return new GalleryView(
                row.id(),
                row.sessionId(),
                row.title(),
                row.status(),
                row.downloads(),
                row.photoCount(),
                row.archivedAt(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private static GalleryRow galleryRow(ResultSet rs, int rowNum) throws SQLException {
        return new GalleryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("title"),
                GalleryStatus.valueOf(rs.getString("status")),
                GalleryDownloads.valueOf(rs.getString("downloads")),
                rs.getInt("photo_count"),
                instant(rs, "archived_at"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record GalleryRow(
            UUID id,
            UUID workspaceId,
            UUID sessionId,
            String title,
            GalleryStatus status,
            GalleryDownloads downloads,
            int photoCount,
            Instant archivedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** A gallery as lists show it. */
    record GalleryView(
            UUID id,
            UUID sessionId,
            String title,
            GalleryStatus status,
            GalleryDownloads downloads,
            int photoCount,
            Instant archivedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** A gallery with its photos in display order; their thumbnail and preview URLs expire after a few minutes. */
    record GalleryDetail(GalleryView gallery, List<AssetView> photos) {}
}
