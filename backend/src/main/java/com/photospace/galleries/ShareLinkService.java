package com.photospace.galleries;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.galleries.GalleryService.GalleryRow;
import com.photospace.shared.SecretTokens;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A gallery's share links, as its photographers manage them. A new link's token is returned once and only its
 * hash is stored, so a lost link cannot be shown again: the photographer creates a new one. Links are revoked,
 * never deleted.
 */
@Service
class ShareLinkService {

    private static final String COLUMNS = """
            l.id, l.expires_at, l.revoked_at, l.created_at,
            CASE WHEN l.revoked_at IS NOT NULL THEN 'REVOKED' WHEN l.expires_at <= now() THEN 'EXPIRED' ELSE 'ACTIVE' END
                AS state,
            (SELECT count(*) FROM gallery_access_events e WHERE e.link_id = l.id AND e.kind = 'VIEW') AS views,
            (SELECT count(*) FROM gallery_access_events e WHERE e.link_id = l.id AND e.kind = 'DOWNLOAD') AS downloads,
            (SELECT max(e.occurred_at) FROM gallery_access_events e WHERE e.link_id = l.id) AS last_accessed_at""";

    private final JdbcClient jdbc;
    private final GalleryService galleries;
    private final GalleryProperties properties;
    private final AuditService audit;

    ShareLinkService(JdbcClient jdbc, GalleryService galleries, GalleryProperties properties, AuditService audit) {
        this.jdbc = jdbc;
        this.galleries = galleries;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * Creates a link that stays valid for {@code validDays} periods of exactly 24 hours. (A calendar-day interval
     * would follow the database session's time zone and gain or lose an hour across a daylight-saving change.) A link to a draft gallery is allowed; it opens the
     * gallery once it is published.
     */
    @Transactional
    CreatedLink create(WorkspaceContext workspace, UUID galleryId, int validDays) {
        GalleryRow gallery = galleries.find(workspace.workspaceId(), galleryId, false).orElseThrow(NotFoundException::new);
        GalleryService.requireEditable(gallery);
        if (validDays < 1 || validDays > properties.maxLinkDays()) {
            throw InvalidFieldsException.of("validDays", "must be between 1 and " + properties.maxLinkDays());
        }
        String token = SecretTokens.generate();
        UUID id = jdbc.sql("""
                        INSERT INTO gallery_share_links (workspace_id, gallery_id, token_hash, expires_at, created_by)
                        VALUES (:workspaceId, :galleryId, :tokenHash, now() + make_interval(hours => 24 * :validDays), :userId)
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("galleryId", galleryId)
                .param("tokenHash", SecretTokens.hash(token))
                .param("validDays", validDays)
                .param("userId", workspace.userId())
                .query(UUID.class)
                .single();
        audit.record(workspace, AuditAction.SHARE_LINK_CREATED, AuditTarget.SHARE_LINK, id, "valid " + validDays + " days");
        return new CreatedLink(get(workspace, galleryId, id), token);
    }

    /** The gallery's links, newest first, with how often each was used. */
    @Transactional(readOnly = true)
    PageView<LinkView> list(WorkspaceContext workspace, UUID galleryId, PageQuery page) {
        galleries.find(workspace.workspaceId(), galleryId, false).orElseThrow(NotFoundException::new);
        String where = " FROM gallery_share_links l WHERE l.workspace_id = :workspaceId AND l.gallery_id = :galleryId";
        long total = jdbc.sql("SELECT count(*)" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("galleryId", galleryId)
                .query(Long.class)
                .single();
        List<LinkView> rows = jdbc.sql("SELECT " + COLUMNS + where + " ORDER BY l.created_at DESC, l.id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("galleryId", galleryId)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(ShareLinkService::linkView)
                .list();
        return PageView.of(rows, page, total);
    }

    /** Stops the link from working, for good. Revoking a revoked link changes nothing. */
    @Transactional
    LinkView revoke(WorkspaceContext workspace, UUID galleryId, UUID linkId) {
        int revoked = jdbc.sql("""
                        UPDATE gallery_share_links SET revoked_at = now(), revoked_by = :userId
                         WHERE id = :id AND workspace_id = :workspaceId AND gallery_id = :galleryId AND revoked_at IS NULL
                        """)
                .param("userId", workspace.userId())
                .param("id", linkId)
                .param("workspaceId", workspace.workspaceId())
                .param("galleryId", galleryId)
                .update();
        if (revoked == 1) {
            audit.record(workspace, AuditAction.SHARE_LINK_REVOKED, AuditTarget.SHARE_LINK, linkId, null);
        }
        return get(workspace, galleryId, linkId);
    }

    private LinkView get(WorkspaceContext workspace, UUID galleryId, UUID linkId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM gallery_share_links l
                        WHERE l.id = :id AND l.workspace_id = :workspaceId AND l.gallery_id = :galleryId
                        """)
                .param("id", linkId)
                .param("workspaceId", workspace.workspaceId())
                .param("galleryId", galleryId)
                .query(ShareLinkService::linkView)
                .optional()
                .orElseThrow(NotFoundException::new);
    }

    private static LinkView linkView(ResultSet rs, int rowNum) throws SQLException {
        return new LinkView(
                rs.getObject("id", UUID.class),
                LinkState.valueOf(rs.getString("state")),
                GalleryService.instant(rs, "expires_at"),
                GalleryService.instant(rs, "revoked_at"),
                GalleryService.instant(rs, "created_at"),
                rs.getLong("views"),
                rs.getLong("downloads"),
                GalleryService.instant(rs, "last_accessed_at"));
    }

    /** Whether the link itself still works. It opens the gallery only while the gallery is also published. */
    enum LinkState {
        ACTIVE,
        EXPIRED,
        REVOKED
    }

    /** A link as its photographers see it. It never includes the token, which is not stored. */
    record LinkView(
            UUID id,
            LinkState state,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            long views,
            long downloads,
            Instant lastAccessedAt) {}

    /** A new link and its token, returned once. The token is a credential: never log or store it. */
    record CreatedLink(LinkView link, String token) {}
}
