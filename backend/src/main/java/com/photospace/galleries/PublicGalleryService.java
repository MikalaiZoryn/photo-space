package com.photospace.galleries;

import com.photospace.assets.AssetService;
import com.photospace.assets.PhotoFile;
import com.photospace.galleries.GalleryService.GalleryRow;
import com.photospace.shared.web.ForbiddenException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gallery access for visitors, who hold a share link token and nothing else. The token resolves to exactly one
 * gallery, and only while the link is neither expired nor revoked and the gallery is published and not archived.
 * Every other case (malformed, unknown, expired, revoked, unpublished, archived) is the same 404, so a token
 * reveals nothing about why it does not work. Views and downloads are recorded per link, without visitor data.
 */
@Service
class PublicGalleryService {

    private final JdbcClient jdbc;
    private final GalleryService galleries;
    private final AssetService assets;
    private final WorkspaceService workspaces;
    private final GalleryProperties properties;

    PublicGalleryService(JdbcClient jdbc, GalleryService galleries, AssetService assets, WorkspaceService workspaces,
            GalleryProperties properties) {
        this.jdbc = jdbc;
        this.galleries = galleries;
        this.assets = assets;
        this.workspaces = workspaces;
        this.properties = properties;
    }

    /** The gallery the token opens, with short-lived preview URLs. Records a view. */
    @Transactional
    PublicGalleryView view(String token) {
        Access access = resolve(token).orElseThrow(NotFoundException::new);
        record(access, "VIEW", null);
        GalleryRow gallery = access.gallery();
        List<PublicPhoto> photos = galleries.photos(gallery).stream()
                .map(photo -> new PublicPhoto(
                        photo.id(), photo.width(), photo.height(), photo.thumbnailUrl(), photo.previewUrl()))
                .toList();
        String studio = workspaces.name(gallery.workspaceId()).orElseThrow(NotFoundException::new);
        return new PublicGalleryView(gallery.title(), studio, gallery.downloads(), access.expiresAt(), photos);
    }

    /**
     * A short-lived URL that downloads one photo of the gallery, if the gallery allows downloads: the preview or
     * the original, as the gallery's setting says. Records a download.
     */
    @Transactional
    DownloadView download(String token, UUID assetId) {
        Access access = resolve(token).orElseThrow(NotFoundException::new);
        GalleryRow gallery = access.gallery();
        boolean inGallery = jdbc.sql("""
                        SELECT count(*) FROM gallery_assets
                         WHERE gallery_id = :galleryId AND workspace_id = :workspaceId AND asset_id = :assetId
                        """)
                .param("galleryId", gallery.id())
                .param("workspaceId", gallery.workspaceId())
                .param("assetId", assetId)
                .query(Long.class)
                .single() > 0;
        if (!inGallery) {
            throw new NotFoundException();
        }
        PhotoFile file = switch (gallery.downloads()) {
            case NONE -> throw new ForbiddenException();
            case PREVIEWS -> PhotoFile.PREVIEW;
            case ORIGINALS -> PhotoFile.ORIGINAL;
        };
        URI url = assets.downloadUrl(gallery.workspaceId(), assetId, file, properties.downloadUrlValidity())
                .orElseThrow(NotFoundException::new);
        record(access, "DOWNLOAD", assetId);
        return new DownloadView(url, Instant.now().plus(properties.downloadUrlValidity()));
    }

    private Optional<Access> resolve(String token) {
        if (!ShareTokens.isWellFormed(token)) {
            return Optional.empty();
        }
        Optional<LinkRow> link = jdbc.sql("""
                        SELECT l.id, l.workspace_id, l.gallery_id, l.expires_at
                          FROM gallery_share_links l
                         WHERE l.token_hash = :tokenHash AND l.revoked_at IS NULL AND l.expires_at > now()
                        """)
                .param("tokenHash", ShareTokens.hash(token))
                .query((rs, i) -> new LinkRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("gallery_id", UUID.class),
                        GalleryService.instant(rs, "expires_at")))
                .optional();
        return link.flatMap(l -> galleries.find(l.workspaceId(), l.galleryId(), false)
                .filter(gallery -> gallery.status() == GalleryStatus.PUBLISHED && gallery.archivedAt() == null)
                .map(gallery -> new Access(l.id(), l.expiresAt(), gallery)));
    }

    private void record(Access access, String kind, UUID assetId) {
        jdbc.sql("""
                        INSERT INTO gallery_access_events (workspace_id, link_id, kind, asset_id)
                        VALUES (:workspaceId, :linkId, :kind, :assetId)
                        """)
                .param("workspaceId", access.gallery().workspaceId())
                .param("linkId", access.linkId())
                .param("kind", kind)
                .param("assetId", assetId)
                .update();
    }

    private record LinkRow(UUID id, UUID workspaceId, UUID galleryId, Instant expiresAt) {}

    private record Access(UUID linkId, Instant expiresAt, GalleryRow gallery) {}

    /** What a visitor sees: no IDs of the workspace, session, or link, and no file names. */
    record PublicGalleryView(
            String title, String studioName, GalleryDownloads downloads, Instant linkExpiresAt, List<PublicPhoto> photos) {}

    /** One photo. The URLs expire after a few minutes; the page reloads the gallery to get fresh ones. */
    record PublicPhoto(UUID id, Integer width, Integer height, URI thumbnailUrl, URI previewUrl) {}

    /** A download URL. It is a credential for one file: never log it. */
    record DownloadView(URI url, Instant expiresAt) {}
}
