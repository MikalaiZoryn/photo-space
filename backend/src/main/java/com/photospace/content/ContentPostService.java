package com.photospace.content;

import com.photospace.assets.AssetService;
import com.photospace.assets.AssetService.AssetView;
import com.photospace.assets.Crop;
import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.content.ContentService.DraftView;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The posts of session plans: the calendar's items, their photos and 4:5 crops, their day, and "posted", which a
 * person sets after posting by hand. A post's text is its draft, edited and approved through {@link ContentService}.
 * Every query is scoped by the verified workspace.
 */
@Service
class ContentPostService {

    /** How long post kit download links work. */
    static final Duration KIT_URL_VALIDITY = Duration.ofMinutes(10);

    private static final String POST_COLUMNS = """
            p.id, p.plan_id, p.session_id, s.title AS session_title, p.draft_id, p.stage, p.format, p.idea,
            p.offset_days, p.planned_on, p.posted_on, p.post_url, p.regenerating, p.failure_reason, p.version,
            p.updated_at, d.status AS draft_status, d.version AS draft_version, v.caption, v.call_to_action,
            v.hashtags,
            CASE WHEN p.posted_on IS NOT NULL THEN 'POSTED'
                 WHEN d.status = 'APPROVED' AND p.planned_on IS NOT NULL THEN 'SCHEDULED'
                 WHEN d.status = 'APPROVED' THEN 'APPROVED'
                 ELSE 'PROPOSED' END AS post_status,
            (SELECT count(*) FROM content_post_photos x WHERE x.post_id = p.id) AS photo_count,
            (SELECT x.asset_id FROM content_post_photos x WHERE x.post_id = p.id ORDER BY x.position LIMIT 1)
                AS cover_asset_id""";
    private static final String POST_FROM = """
             FROM content_posts p
             JOIN content_drafts d ON d.workspace_id = p.workspace_id AND d.id = p.draft_id
             JOIN content_draft_versions v ON v.draft_id = d.id AND v.version = d.version
             JOIN photo_sessions s ON s.workspace_id = p.workspace_id AND s.id = p.session_id
            """;
    /**
     * Calendar order: by the day a post shows on (when it was posted, else when it is planned; undated last), then
     * stage, then feed posts before Reels and Stories.
     */
    private static final String POST_ORDER = """
            coalesce(p.posted_on, p.planned_on) NULLS LAST, array_position(ARRAY['SNEAK_PEEK','DELIVERY','DRIP'], p.stage),
            p.offset_days, array_position(ARRAY['SINGLE','CAROUSEL','REEL','STORY'], p.format), p.id""";
    private static final String POST_DAY = "coalesce(p.posted_on, p.planned_on)";

    private final JdbcClient jdbc;
    private final ContentService content;
    private final AssetService assets;
    private final AuditService audit;

    ContentPostService(JdbcClient jdbc, ContentService content, AssetService assets, AuditService audit) {
        this.jdbc = jdbc;
        this.content = content;
        this.assets = assets;
        this.audit = audit;
    }

    /**
     * Posts in calendar order: by day (undated last), then stage. {@code from} and {@code to} keep posts whose day
     * falls in the range, inclusive; {@code unscheduled} keeps approved posts without a day.
     */
    @Transactional(readOnly = true)
    PageView<PostView> list(WorkspaceContext workspace, PostFilter filter, PageQuery page) {
        StringBuilder where = new StringBuilder(" WHERE p.workspace_id = :workspaceId");
        if (filter.sessionId() != null) {
            where.append(" AND p.session_id = :sessionId");
        }
        if (filter.from() != null) {
            where.append(" AND ").append(POST_DAY).append(" >= :from");
        }
        if (filter.to() != null) {
            where.append(" AND ").append(POST_DAY).append(" <= :to");
        }
        if (filter.unscheduled()) {
            where.append(" AND p.planned_on IS NULL AND p.posted_on IS NULL AND d.status = 'APPROVED'");
        }
        String statusFilter = filter.status() == null ? "" : " WHERE post_status = :status";
        String select = "SELECT * FROM (SELECT " + POST_COLUMNS + POST_FROM + where + ") posts" + statusFilter;
        long total = bind(jdbc.sql("SELECT count(*) FROM (" + select + ") counted"), workspace, filter)
                .query(Long.class)
                .single();
        List<PostRow> rows = bind(jdbc.sql(select + " ORDER BY " + POST_ORDER.replace("p.", "")
                        + " LIMIT :limit OFFSET :offset"), workspace, filter)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(ContentPostService::postRow)
                .list();
        return PageView.of(views(workspace.workspaceId(), rows), page, total);
    }

    /** One post with its photos (each with its effective crop) and its draft with review hints and caption rules. */
    @Transactional(readOnly = true)
    PostDetail get(WorkspaceContext workspace, UUID postId) {
        PostRow row = find(workspace.workspaceId(), postId, false).orElseThrow(NotFoundException::new);
        return detail(workspace.workspaceId(), row);
    }

    /** Sets or clears the day to post. A person's choice: the post is kept when the plan is refreshed. */
    @Transactional
    PostView schedule(WorkspaceContext workspace, UUID postId, int version, LocalDate plannedOn) {
        lock(workspace, postId, version);
        jdbc.sql("""
                        UPDATE content_posts SET planned_on = :plannedOn, touched = true, version = version + 1,
                                                 updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("plannedOn", plannedOn)
                .param("id", postId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return view(workspace.workspaceId(), postId);
    }

    /**
     * Replaces the post's photos, in posting order, each with an optional 4:5 crop. They must be ready photos of
     * the post's session, within the format's limit. An approved post goes back to review, because its approval
     * covered the earlier photos.
     */
    @Transactional
    PostDetail updatePhotos(WorkspaceContext workspace, UUID postId, int version, List<PostPhotoInput> photos) {
        PostRow post = lock(workspace, postId, version);
        if (post.postedOn() != null) {
            throw new ConflictException("This post is marked as posted. Mark it as not posted before changing its photos.");
        }
        List<UUID> ids = photos.stream().map(PostPhotoInput::assetId).toList();
        if (ids.isEmpty()) {
            throw InvalidFieldsException.of("photos", "must contain at least one photo");
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw InvalidFieldsException.of("photos", "must not contain the same photo twice");
        }
        PostFormat format = post.format();
        if (format == PostFormat.SINGLE && ids.size() > 1) {
            format = PostFormat.CAROUSEL;
        } else if (format == PostFormat.CAROUSEL && ids.size() == 1) {
            format = PostFormat.SINGLE;
        }
        if (ids.size() > format.maxPhotos()) {
            throw InvalidFieldsException.of("photos", "must have at most " + format.maxPhotos() + " photos");
        }
        Map<UUID, AssetService.ReadyPhoto> ready = assets.readyPhotos(workspace.workspaceId(), post.sessionId()).stream()
                .collect(Collectors.toMap(AssetService.ReadyPhoto::id, photo -> photo));
        for (PostPhotoInput photo : photos) {
            AssetService.ReadyPhoto found = ready.get(photo.assetId());
            if (found == null) {
                throw InvalidFieldsException.of("photos", "must be ready photos of this post's session");
            }
            if (photo.crop() != null && !photo.crop().fits(found.width(), found.height())) {
                throw InvalidFieldsException.of("photos", "crops must be 4:5 and inside the photo");
            }
        }
        jdbc.sql("DELETE FROM content_post_photos WHERE post_id = :id AND workspace_id = :workspaceId")
                .param("id", postId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        insertPhotos(workspace.workspaceId(), post.sessionId(), postId, photos);
        jdbc.sql("""
                        UPDATE content_posts SET format = :format, touched = true, version = version + 1,
                                                 updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("format", format.name())
                .param("id", postId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        content.withdrawApproval(workspace.workspaceId(), post.draftId());
        return get(workspace, postId);
    }

    /**
     * Records that a person posted it by hand, on {@code postedOn}, optionally with the post's link; or takes that
     * back. Only an approved post can be marked as posted. Nothing is posted to Instagram from here.
     */
    @Transactional
    PostView markPosted(WorkspaceContext workspace, UUID postId, int version, boolean posted, LocalDate postedOn,
            String postUrl) {
        PostRow post = lock(workspace, postId, version);
        if (posted && post.draftStatus() != DraftStatus.APPROVED) {
            throw new ConflictException("Approve the post before marking it as posted.");
        }
        if (posted && postedOn == null) {
            throw InvalidFieldsException.of("postedOn", "must be set when marking the post as posted");
        }
        if (posted == (post.postedOn() != null) && Objects.equals(postedOn, post.postedOn())
                && Objects.equals(postUrl, post.postUrl())) {
            return view(workspace.workspaceId(), postId);
        }
        jdbc.sql("""
                        UPDATE content_posts
                           SET posted_on = :postedOn, post_url = :postUrl,
                               posted_by = CASE WHEN :posted THEN CAST(:userId AS uuid) END,
                               touched = true, version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("postedOn", posted ? postedOn : null)
                .param("postUrl", posted ? postUrl : null)
                .param("posted", posted)
                .param("userId", workspace.userId())
                .param("id", postId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, posted ? AuditAction.CONTENT_POST_POSTED : AuditAction.CONTENT_POST_UNPOSTED,
                AuditTarget.CONTENT_POST, postId, posted ? "posted on " + postedOn : null);
        return view(workspace.workspaceId(), postId);
    }

    /** Drops a post from its plan, with its text. A post marked as posted stays. */
    @Transactional
    void delete(WorkspaceContext workspace, UUID postId, int version) {
        PostRow post = lock(workspace, postId, version);
        if (post.postedOn() != null) {
            throw new ConflictException("This post is marked as posted, so it stays in the plan.");
        }
        jdbc.sql("DELETE FROM content_drafts WHERE id = :draftId AND workspace_id = :workspaceId")
                .param("draftId", post.draftId())
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.CONTENT_POST_DELETED, AuditTarget.CONTENT_POST, postId,
                post.stage() + " " + post.format());
    }

    /**
     * Adds a post a person wrote to a session's plan. Its text is an edited version 1 by them, waiting for
     * approval like any other post.
     */
    @Transactional
    PostDetail add(WorkspaceContext workspace, UUID planId, UUID sessionId, UUID batchId, PostInput input) {
        PostFormat format = input.format();
        if (format == PostFormat.SINGLE && input.photos().size() > 1) {
            format = PostFormat.CAROUSEL;
        } else if (format == PostFormat.CAROUSEL && input.photos().size() == 1) {
            format = PostFormat.SINGLE;
        }
        if (input.photos().isEmpty() || input.photos().size() > format.maxPhotos()) {
            throw InvalidFieldsException.of("photos", "must have 1 to " + format.maxPhotos() + " photos");
        }
        Map<UUID, AssetService.ReadyPhoto> ready = assets.readyPhotos(workspace.workspaceId(), sessionId).stream()
                .collect(Collectors.toMap(AssetService.ReadyPhoto::id, photo -> photo));
        if (new LinkedHashSet<>(input.photos()).size() != input.photos().size()
                || !ready.keySet().containsAll(input.photos())) {
            throw InvalidFieldsException.of("photos", "must be different ready photos of this session");
        }
        DraftText text = ContentService.checked(input.draft());
        UUID draftId = content.insertDraft(workspace.workspaceId(), batchId, text, workspace.userId());
        UUID postId = jdbc.sql("""
                        INSERT INTO content_posts (workspace_id, session_id, plan_id, draft_id, stage, format, idea,
                                                   offset_days, planned_on, touched)
                        VALUES (:workspaceId, :sessionId, :planId, :draftId, :stage, :format, :idea, 0, :plannedOn, true)
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .param("planId", planId)
                .param("draftId", draftId)
                .param("stage", input.stage().name())
                .param("format", format.name())
                .param("idea", input.idea())
                .param("plannedOn", input.plannedOn())
                .query(UUID.class)
                .single();
        insertPhotos(workspace.workspaceId(), sessionId, postId,
                input.photos().stream().map(id -> new PostPhotoInput(id, null)).toList());
        return get(workspace, postId);
    }

    /**
     * Queues a new caption for this post alone, from its photos. The current text stays until the new one arrives;
     * then it is the draft's next version and needs approval again. Asking again while one is queued changes nothing.
     */
    @Transactional
    PostView regenerate(WorkspaceContext workspace, UUID postId, int version) {
        PostRow post = lock(workspace, postId, version);
        if (post.postedOn() != null) {
            throw new ConflictException("This post is marked as posted. Mark it as not posted before rewriting it.");
        }
        if (!post.regenerating()) {
            int next = jdbc.sql("""
                            UPDATE content_posts
                               SET regenerating = true, failure_reason = NULL, touched = true, version = version + 1,
                                   updated_at = now()
                             WHERE id = :id AND workspace_id = :workspaceId
                            RETURNING version
                            """)
                    .param("id", postId)
                    .param("workspaceId", workspace.workspaceId())
                    .query(Integer.class)
                    .single();
            content.enqueuePostRewrite(workspace.workspaceId(), postId, next);
        }
        return view(workspace.workspaceId(), postId);
    }

    /**
     * What the photographer needs to post by hand: the approved text and each photo's 4:5 export (rendered on first
     * request) and original, as short-lived download links.
     */
    @Transactional(readOnly = true)
    PostKit kit(WorkspaceContext workspace, UUID postId) {
        PostDetail detail = get(workspace, postId);
        PostView post = detail.post();
        if (post.status() == PostStatus.PROPOSED) {
            throw new ConflictException("Approve the post before opening its post kit.");
        }
        List<KitPhoto> photos = new ArrayList<>();
        for (PostPhotoView photo : detail.photos()) {
            UUID assetId = photo.photo().id();
            try {
                URI export = assets.exportUrl(workspace.workspaceId(), post.sessionId(), assetId, photo.crop(),
                                KIT_URL_VALIDITY)
                        .orElse(null);
                URI original = assets.originalUrl(workspace.workspaceId(), post.sessionId(), assetId, KIT_URL_VALIDITY)
                        .orElse(null);
                if (export != null) {
                    photos.add(new KitPhoto(assetId, photo.photo().originalFilename(), photo.photo().thumbnailUrl(),
                            export, original));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new PostKit(post, photos);
    }

    // --- Used by plans and the post rewrite job. ---

    /** The posts of one plan, in calendar order. */
    List<PostView> listForPlan(UUID workspaceId, UUID planId) {
        List<PostRow> rows = jdbc.sql("SELECT " + POST_COLUMNS + POST_FROM + " WHERE p.workspace_id = :workspaceId"
                        + " AND p.plan_id = :planId ORDER BY " + POST_ORDER)
                .param("workspaceId", workspaceId)
                .param("planId", planId)
                .query(ContentPostService::postRow)
                .list();
        return views(workspaceId, rows);
    }

    /** Stores a planned post's photos in order, with no crop (the centred default). */
    void insertPhotos(UUID workspaceId, UUID sessionId, UUID postId, List<PostPhotoInput> photos) {
        for (int position = 0; position < photos.size(); position++) {
            PostPhotoInput photo = photos.get(position);
            Crop crop = photo.crop();
            jdbc.sql("""
                            INSERT INTO content_post_photos (post_id, asset_id, workspace_id, session_id, position,
                                                             crop_x, crop_y, crop_width, crop_height)
                            VALUES (:postId, :assetId, :workspaceId, :sessionId, :position, :x, :y, :w, :h)
                            """)
                    .param("postId", postId)
                    .param("assetId", photo.assetId())
                    .param("workspaceId", workspaceId)
                    .param("sessionId", sessionId)
                    .param("position", position)
                    .param("x", crop == null ? null : crop.x())
                    .param("y", crop == null ? null : crop.y())
                    .param("w", crop == null ? null : crop.width())
                    .param("h", crop == null ? null : crop.height())
                    .update();
        }
    }

    /** A post waiting for its rewrite, for the job that runs without a signed-in user. */
    Optional<PendingRewrite> findPendingRewrite(UUID workspaceId, UUID postId) {
        return find(workspaceId, postId, false)
                .filter(PostRow::regenerating)
                .map(post -> new PendingRewrite(post.id(), workspaceId, post.sessionId(), post.draftId(),
                        jdbc.sql("""
                                        SELECT b.session_type FROM content_drafts d
                                          JOIN content_batches b ON b.workspace_id = d.workspace_id AND b.id = d.batch_id
                                         WHERE d.id = :draftId AND d.workspace_id = :workspaceId""")
                                .param("draftId", post.draftId())
                                .param("workspaceId", workspaceId)
                                .query(String.class)
                                .optional()
                                .orElse(null),
                        post.format() == PostFormat.STORY,
                        photoIds(workspaceId, post.id())));
    }

    /** Ends a rewrite: the new text as the draft's next version, or, with {@code text} null, the failure reason. */
    @Transactional
    void finishRewrite(UUID workspaceId, UUID postId, DraftText text, String failureReason) {
        Optional<UUID> draftId = jdbc.sql("""
                        UPDATE content_posts SET regenerating = false, failure_reason = :reason, version = version + 1,
                                                 updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND regenerating
                        RETURNING draft_id
                        """)
                .param("reason", failureReason)
                .param("id", postId)
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .optional();
        if (draftId.isPresent() && text != null) {
            content.appendGeneratedVersion(workspaceId, draftId.get(), text);
        }
    }

    // --- Internals. ---

    private <T extends JdbcClient.StatementSpec> T bind(T spec, WorkspaceContext workspace, PostFilter filter) {
        spec.param("workspaceId", workspace.workspaceId())
                .param("sessionId", filter.sessionId())
                .param("from", filter.from())
                .param("to", filter.to())
                .param("status", filter.status() == null ? null : filter.status().name());
        return spec;
    }

    private Optional<PostRow> find(UUID workspaceId, UUID postId, boolean forUpdate) {
        if (forUpdate) {
            Optional<UUID> locked = jdbc.sql(
                            "SELECT id FROM content_posts WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE")
                    .param("id", postId)
                    .param("workspaceId", workspaceId)
                    .query(UUID.class)
                    .optional();
            if (locked.isEmpty()) {
                return Optional.empty();
            }
        }
        return jdbc.sql("SELECT " + POST_COLUMNS + POST_FROM + " WHERE p.id = :id AND p.workspace_id = :workspaceId")
                .param("id", postId)
                .param("workspaceId", workspaceId)
                .query(ContentPostService::postRow)
                .optional();
    }

    private PostRow lock(WorkspaceContext workspace, UUID postId, int version) {
        PostRow post = find(workspace.workspaceId(), postId, true).orElseThrow(NotFoundException::new);
        if (post.version() != version) {
            throw new ConflictException("This post was changed by someone else. Reload it and try again.");
        }
        return post;
    }

    private PostView view(UUID workspaceId, UUID postId) {
        return views(workspaceId, List.of(find(workspaceId, postId, false).orElseThrow())).getFirst();
    }

    private PostDetail detail(UUID workspaceId, PostRow row) {
        List<StoredPhoto> stored = jdbc.sql("""
                        SELECT asset_id, crop_x, crop_y, crop_width, crop_height FROM content_post_photos
                         WHERE post_id = :id AND workspace_id = :workspaceId ORDER BY position""")
                .param("id", row.id())
                .param("workspaceId", workspaceId)
                .query((rs, n) -> new StoredPhoto(rs.getObject("asset_id", UUID.class),
                        rs.getObject("crop_x") == null ? null : new Crop(rs.getDouble("crop_x"), rs.getDouble("crop_y"),
                                rs.getDouble("crop_width"), rs.getDouble("crop_height"))))
                .list();
        Map<UUID, AssetView> ready = assets.findReady(workspaceId, row.sessionId(),
                stored.stream().map(StoredPhoto::assetId).toList());
        List<PostPhotoView> photos = new ArrayList<>();
        for (StoredPhoto photo : stored) {
            AssetView asset = ready.get(photo.assetId());
            if (asset != null) {
                Crop crop = photo.crop() != null
                        ? photo.crop()
                        : Crop.centred(asset.width() == null ? 4 : asset.width(), asset.height() == null ? 5 : asset.height());
                photos.add(new PostPhotoView(asset, crop, photo.crop() != null));
            }
        }
        DraftView draft = content.findDrafts(workspaceId, List.of(row.draftId())).get(row.draftId());
        return new PostDetail(views(workspaceId, List.of(row)).getFirst(), photos, draft);
    }

    private List<UUID> photoIds(UUID workspaceId, UUID postId) {
        return jdbc.sql("""
                        SELECT asset_id FROM content_post_photos
                         WHERE post_id = :id AND workspace_id = :workspaceId ORDER BY position""")
                .param("id", postId)
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .list();
    }

    /** Views with each post's cover thumbnail, looked up once per session. */
    private List<PostView> views(UUID workspaceId, List<PostRow> rows) {
        Map<UUID, List<UUID>> coversBySession = new HashMap<>();
        for (PostRow row : rows) {
            if (row.coverAssetId() != null) {
                coversBySession.computeIfAbsent(row.sessionId(), id -> new ArrayList<>()).add(row.coverAssetId());
            }
        }
        Map<UUID, URI> thumbnails = new HashMap<>();
        coversBySession.forEach((sessionId, ids) -> assets.findReady(workspaceId, sessionId, ids)
                .forEach((id, asset) -> thumbnails.put(id, asset.thumbnailUrl())));
        return rows.stream().map(row -> row.view(thumbnails.get(row.coverAssetId()))).toList();
    }

    private static PostRow postRow(ResultSet rs, int rowNum) throws SQLException {
        Array hashtags = rs.getArray("hashtags");
        return new PostRow(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("session_title"),
                rs.getObject("draft_id", UUID.class),
                PostStage.valueOf(rs.getString("stage")),
                PostFormat.valueOf(rs.getString("format")),
                PostStatus.valueOf(rs.getString("post_status")),
                rs.getString("idea"),
                rs.getInt("offset_days"),
                rs.getObject("planned_on", LocalDate.class),
                rs.getObject("posted_on", LocalDate.class),
                rs.getString("post_url"),
                rs.getBoolean("regenerating"),
                rs.getString("failure_reason"),
                rs.getInt("version"),
                DraftStatus.valueOf(rs.getString("draft_status")),
                rs.getInt("draft_version"),
                rs.getString("caption"),
                rs.getString("call_to_action"),
                hashtags == null ? List.of() : List.of((String[]) hashtags.getArray()),
                rs.getInt("photo_count"),
                rs.getObject("cover_asset_id", UUID.class),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private record StoredPhoto(UUID assetId, Crop crop) {}

    private record PostRow(
            UUID id,
            UUID planId,
            UUID sessionId,
            String sessionTitle,
            UUID draftId,
            PostStage stage,
            PostFormat format,
            PostStatus status,
            String idea,
            int offsetDays,
            LocalDate plannedOn,
            LocalDate postedOn,
            String postUrl,
            boolean regenerating,
            String failureReason,
            int version,
            DraftStatus draftStatus,
            int draftVersion,
            String caption,
            String callToAction,
            List<String> hashtags,
            int photoCount,
            UUID coverAssetId,
            Instant updatedAt) {

        PostView view(URI coverThumbnailUrl) {
            return new PostView(id, planId, sessionId, sessionTitle, draftId, stage, format, status, idea, offsetDays,
                    plannedOn, postedOn, postUrl, regenerating, failureReason, version, draftStatus, draftVersion,
                    caption, callToAction, hashtags, photoCount, coverThumbnailUrl, updatedAt);
        }
    }

    /** Which posts to list. Every field is optional. */
    record PostFilter(UUID sessionId, LocalDate from, LocalDate to, PostStatus status, boolean unscheduled) {}

    /** A photo for a post, with an optional 4:5 crop; none means the centred default. */
    record PostPhotoInput(UUID assetId, Crop crop) {}

    /** A post waiting for its rewrite, with its photos in posting order. */
    record PendingRewrite(
            UUID id, UUID workspaceId, UUID sessionId, UUID draftId, String sessionType, boolean story,
            List<UUID> assetIds) {}

    /**
     * A post as lists and the calendar show it. {@code status} is derived: {@code PROPOSED} until its draft is
     * approved, then {@code APPROVED} without a day or {@code SCHEDULED} with one, and {@code POSTED} once a person
     * marks it. {@code coverThumbnailUrl} expires after a few minutes.
     */
    record PostView(
            UUID id,
            UUID planId,
            UUID sessionId,
            String sessionTitle,
            UUID draftId,
            PostStage stage,
            PostFormat format,
            PostStatus status,
            String idea,
            int offsetDays,
            LocalDate plannedOn,
            LocalDate postedOn,
            String postUrl,
            boolean regenerating,
            String failureReason,
            int version,
            DraftStatus draftStatus,
            int draftVersion,
            String caption,
            String callToAction,
            List<String> hashtags,
            int photoCount,
            URI coverThumbnailUrl,
            Instant updatedAt) {}

    /** A post's photo with its effective crop; {@code customCrop} is false for the centred default. */
    record PostPhotoView(AssetView photo, Crop crop, boolean customCrop) {}

    record PostDetail(PostView post, List<PostPhotoView> photos, DraftView draft) {}

    /** One photo of the post kit: its 4:5 export and its original, as download links that expire in minutes. */
    record KitPhoto(UUID assetId, String filename, URI thumbnailUrl, URI exportUrl, URI originalUrl) {}

    record PostKit(PostView post, List<KitPhoto> photos) {}
}
