package com.photospace.assets;

import com.photospace.jobs.JobService;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.storage.ObjectStorage;
import com.photospace.storage.ObjectStorage.PresignedUpload;
import com.photospace.workspaces.WorkspaceContext;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace's photo assets: upload authorization, upload completion, and reads. Every query is scoped by the
 * verified workspace. Processing results are written by {@link ProcessAssetJob} through the package-private
 * methods at the bottom, which only move an asset out of {@code PROCESSING}.
 */
@Service
public class AssetService {

    private static final String COLUMNS = """
            id, workspace_id, session_id, status, original_filename, content_type, byte_size, original_key,
            thumbnail_key, preview_key, checksum_sha256, width, height, failure_reason,
            uploaded_at, processed_at, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final PhotoSessionService sessions;
    private final ObjectStorage storage;
    private final JobService jobs;
    private final AssetProperties properties;

    AssetService(JdbcClient jdbc, PhotoSessionService sessions, ObjectStorage storage, JobService jobs,
            AssetProperties properties) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.storage = storage;
        this.jobs = jobs;
        this.properties = properties;
    }

    /**
     * Records a pending asset in the session and returns a short-lived URL to upload its original to. The
     * object key is generated here; the URL accepts only the declared type and size.
     */
    @Transactional
    UploadView createUpload(WorkspaceContext workspace, UUID sessionId, UploadInput input) {
        SessionSummary session = sessions.findSummary(workspace, sessionId).orElseThrow(NotFoundException::new);
        Map<String, String> errors = new LinkedHashMap<>();
        String filename = FileNames.forDisplay(input.filename());
        if (filename == null) {
            errors.put("filename", "must contain a printable name");
        }
        Optional<ImageType> type = ImageType.fromContentType(input.contentType());
        if (type.isEmpty()) {
            errors.put("contentType", "must be " + ImageType.acceptedList());
        }
        if (input.byteSize() > properties.maxUploadBytes()) {
            errors.put("byteSize", "must be at most " + megabytes(properties.maxUploadBytes()) + " MB");
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (session.archived()) {
            throw new ConflictException("This session is archived. Restore it before uploading photos.");
        }
        requireWithinLimits(workspace, input.byteSize());

        UUID assetId = UUID.randomUUID();
        String key = AssetKeys.original(workspace.workspaceId(), assetId);
        jdbc.sql("""
                        INSERT INTO photo_assets
                               (id, workspace_id, session_id, status, original_filename, content_type, byte_size, original_key)
                        VALUES (:id, :workspaceId, :sessionId, 'PENDING_UPLOAD', :filename, :contentType, :byteSize, :key)
                        """)
                .param("id", assetId)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .param("filename", filename)
                .param("contentType", type.get().contentType)
                .param("byteSize", input.byteSize())
                .param("key", key)
                .update();
        PresignedUpload upload = storage.presignUpload(
                key, type.get().contentType, input.byteSize(), properties.uploadUrlValidity());
        return new UploadView(
                get(workspace, assetId), new UploadTarget(upload.url(), "PUT", upload.headers(), upload.expiresAt()));
    }

    /**
     * The browser reports that its upload finished. If the object is there, the asset moves to
     * {@code PROCESSING} and its processing job is queued in the same transaction. Completing an asset that is
     * already past this step changes nothing, so a retried request is harmless.
     */
    @Transactional
    AssetView complete(WorkspaceContext workspace, UUID assetId) {
        AssetRow asset = find(workspace.workspaceId(), assetId, true).orElseThrow(NotFoundException::new);
        if (asset.status() != AssetStatus.PENDING_UPLOAD) {
            return view(asset);
        }
        if (storage.size(asset.originalKey()).isEmpty()) {
            throw new ConflictException("The file has not been uploaded yet. Upload it, then try again.");
        }
        jdbc.sql("""
                        UPDATE photo_assets SET status = 'PROCESSING', uploaded_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING_UPLOAD'
                        """)
                .param("id", assetId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        jobs.enqueue(workspace.workspaceId(), ProcessAssetJob.TYPE, assetId, ProcessAssetJob.TYPE + ":" + assetId);
        return get(workspace, assetId);
    }

    @Transactional(readOnly = true)
    AssetView get(WorkspaceContext workspace, UUID assetId) {
        return find(workspace.workspaceId(), assetId, false).map(this::view).orElseThrow(NotFoundException::new);
    }

    /** A session's assets in upload order, in every status, so the UI can show progress and failures. */
    @Transactional(readOnly = true)
    PageView<AssetView> listForSession(WorkspaceContext workspace, UUID sessionId, PageQuery page) {
        sessions.findSummary(workspace, sessionId).orElseThrow(NotFoundException::new);
        String where = " FROM photo_assets WHERE workspace_id = :workspaceId AND session_id = :sessionId";
        long total = jdbc.sql("SELECT count(*)" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
        List<AssetRow> rows = jdbc.sql("SELECT " + COLUMNS + where + " ORDER BY created_at, id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(AssetService::assetRow)
                .list();
        return PageView.of(rows, page, total).map(this::view);
    }

    /**
     * Serializes uploads per workspace (a transaction-scoped advisory lock), then checks the number of
     * unfinished uploads and the storage quota. Rejected and failed assets do not count.
     */
    private void requireWithinLimits(WorkspaceContext workspace, long byteSize) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:workspaceId AS text), 4))")
                .param("workspaceId", workspace.workspaceId())
                .query()
                .listOfRows();
        Map<String, Object> usage = jdbc.sql("""
                        SELECT coalesce(sum(byte_size), 0) AS used,
                               count(*) FILTER (WHERE status = 'PENDING_UPLOAD') AS pending
                          FROM photo_assets
                         WHERE workspace_id = :workspaceId AND status IN ('PENDING_UPLOAD', 'PROCESSING', 'READY')
                        """)
                .param("workspaceId", workspace.workspaceId())
                .query()
                .singleRow();
        if (((Number) usage.get("pending")).longValue() >= properties.maxPendingUploads()) {
            throw new ConflictException("Too many uploads are in progress. Let them finish, then try again.");
        }
        if (((Number) usage.get("used")).longValue() + byteSize > properties.workspaceQuotaBytes()) {
            throw new ConflictException("This upload would exceed the workspace's storage limit of "
                    + megabytes(properties.workspaceQuotaBytes()) + " MB.");
        }
    }

    // --- Used by galleries, which authorize access to the workspace themselves (membership or a share link). ---

    /** The {@code READY} photos of one session among {@code assetIds}, by ID. Any other ID is left out. */
    @Transactional(readOnly = true)
    public Map<UUID, AssetView> findReady(UUID workspaceId, UUID sessionId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM photo_assets
                        WHERE workspace_id = :workspaceId AND session_id = :sessionId AND status = 'READY'
                          AND id IN (:ids)
                        """)
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .param("ids", assetIds)
                .query(AssetService::assetRow)
                .stream()
                .map(this::view)
                .collect(Collectors.toMap(AssetView::id, Function.identity()));
    }

    /**
     * A short-lived URL that downloads a {@code READY} photo's original or preview as a file named after the
     * uploaded file. Empty if the photo is not {@code READY} or not in this workspace.
     */
    @Transactional(readOnly = true)
    public Optional<URI> downloadUrl(UUID workspaceId, UUID assetId, PhotoFile file, Duration validFor) {
        return find(workspaceId, assetId, false)
                .filter(asset -> asset.status() == AssetStatus.READY)
                .map(asset -> switch (file) {
                    case ORIGINAL -> storage.presignAttachment(
                            asset.originalKey(), asset.originalFilename(), asset.contentType(), validFor);
                    case PREVIEW -> storage.presignAttachment(
                            asset.previewKey(), FileNames.previewOf(asset.originalFilename()), "image/jpeg", validFor);
                });
    }

    // --- Used by AI content generation, a background job that authorizes access when the batch is created. ---

    /**
     * The 2048 px preview JPEG (re-encoded without metadata, D11) of a {@code READY} photo of this session. Empty
     * if the photo is not {@code READY}, not of this session, or not in this workspace.
     */
    public Optional<byte[]> readPreview(UUID workspaceId, UUID sessionId, UUID assetId) throws IOException {
        Optional<AssetRow> asset = find(workspaceId, assetId, false)
                .filter(row -> row.status() == AssetStatus.READY && sessionId.equals(row.sessionId()));
        if (asset.isEmpty()) {
            return Optional.empty();
        }
        Path file = Files.createTempFile("preview-", ".jpg");
        try {
            storage.download(asset.get().previewKey(), file);
            return Optional.of(Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // --- Used by processing and cleanup, which run without a signed-in user. ---

    Optional<AssetRow> findForProcessing(UUID workspaceId, UUID assetId) {
        return find(workspaceId, assetId, false);
    }

    @Transactional
    void markReady(AssetRow asset, ImageType type, String checksum, int width, int height, String thumbnailKey,
            String previewKey) {
        jdbc.sql("""
                        UPDATE photo_assets
                           SET status = 'READY', content_type = :contentType, checksum_sha256 = :checksum,
                               width = :width, height = :height, thumbnail_key = :thumbnailKey,
                               preview_key = :previewKey, processed_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PROCESSING'
                        """)
                .param("contentType", type.contentType)
                .param("checksum", checksum)
                .param("width", width)
                .param("height", height)
                .param("thumbnailKey", thumbnailKey)
                .param("previewKey", previewKey)
                .param("id", asset.id())
                .param("workspaceId", asset.workspaceId())
                .update();
    }

    /** {@code reason} must be a fixed, user-facing sentence. */
    @Transactional
    void markUnusable(UUID workspaceId, UUID assetId, AssetStatus status, String reason) {
        jdbc.sql("""
                        UPDATE photo_assets
                           SET status = :status, failure_reason = :reason, processed_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PROCESSING'
                        """)
                .param("status", status.name())
                .param("reason", reason)
                .param("id", assetId)
                .param("workspaceId", workspaceId)
                .update();
    }

    private Optional<AssetRow> find(UUID workspaceId, UUID assetId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM photo_assets WHERE id = :id AND workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", assetId)
                .param("workspaceId", workspaceId)
                .query(AssetService::assetRow)
                .optional();
    }

    /** Ready assets get short-lived URLs for their derivatives. Originals are never linked from here. */
    private AssetView view(AssetRow row) {
        boolean ready = row.status() == AssetStatus.READY;
        return new AssetView(
                row.id(),
                row.sessionId(),
                row.status(),
                row.originalFilename(),
                row.contentType(),
                row.byteSize(),
                row.width(),
                row.height(),
                row.checksumSha256(),
                row.failureReason(),
                ready ? storage.presignDownload(row.thumbnailKey(), properties.viewUrlValidity()) : null,
                ready ? storage.presignDownload(row.previewKey(), properties.viewUrlValidity()) : null,
                row.uploadedAt(),
                row.processedAt(),
                row.createdAt());
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static AssetRow assetRow(ResultSet rs, int rowNum) throws SQLException {
        return new AssetRow(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                AssetStatus.valueOf(rs.getString("status")),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("byte_size"),
                rs.getString("original_key"),
                rs.getString("thumbnail_key"),
                rs.getString("preview_key"),
                rs.getString("checksum_sha256"),
                rs.getObject("width", Integer.class),
                rs.getObject("height", Integer.class),
                rs.getString("failure_reason"),
                instant(rs, "uploaded_at"),
                instant(rs, "processed_at"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record AssetRow(
            UUID id,
            UUID workspaceId,
            UUID sessionId,
            AssetStatus status,
            String originalFilename,
            String contentType,
            long byteSize,
            String originalKey,
            String thumbnailKey,
            String previewKey,
            String checksumSha256,
            Integer width,
            Integer height,
            String failureReason,
            Instant uploadedAt,
            Instant processedAt,
            Instant createdAt) {}

    /**
     * An asset as the API returns it. {@code thumbnailUrl} and {@code previewUrl} are set only when
     * {@code READY} and expire after a few minutes; the original has no URL here.
     */
    public record AssetView(
            UUID id,
            UUID sessionId,
            AssetStatus status,
            String originalFilename,
            String contentType,
            long byteSize,
            Integer width,
            Integer height,
            String checksumSha256,
            String failureReason,
            URI thumbnailUrl,
            URI previewUrl,
            Instant uploadedAt,
            Instant processedAt,
            Instant createdAt) {}

    /** Where and how the browser uploads the original. The URL is a short-lived credential: never log it. */
    public record UploadTarget(URI url, String method, Map<String, String> headers, Instant expiresAt) {}

    public record UploadView(AssetView asset, UploadTarget upload) {}
}
