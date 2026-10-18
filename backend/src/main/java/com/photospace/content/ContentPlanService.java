package com.photospace.content;

import com.photospace.assets.AssetService;
import com.photospace.content.ContentPostService.PostPhotoInput;
import com.photospace.content.ContentPostService.PostView;
import com.photospace.content.PlanTemplate.PlannedSlot;
import com.photospace.content.PlanValidator.PlannedPost;
import com.photospace.content.PlanValidator.ValidatedPlan;
import com.photospace.galleries.GalleryStatus;
import com.photospace.galleries.GalleryStatusChanged;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session plans: one per session, proposed automatically once its photos settle or on request, and refreshed on
 * request. A plan's posts are written by {@link GeneratePlanJob} in a {@code PLAN} batch; code sets every date.
 * Sneak peeks count from the plan's creation, delivery and drip posts from the day the session's gallery is first
 * published. Every query is scoped by the verified workspace, or by the job's or event's workspace.
 */
@Service
class ContentPlanService {

    static final String NO_PHOTOS = "Upload photos to this session first. Plans are written from ready photos.";
    static final String ALL_PHOTOS_USED =
            "Every ready photo is already in a post you kept. Upload more photos to refresh the plan.";
    static final String NOTHING_TO_REFRESH =
            "Every post of the plan is already approved, edited, or posted, so there was nothing to refresh.";

    private static final Logger log = LoggerFactory.getLogger(ContentPlanService.class);

    private static final String PLAN_COLUMNS = """
            p.id, p.session_id, s.title AS session_title, p.origin, p.batch_id, p.published_on, p.created_at,
            p.updated_at, b.status, b.failure_reason, b.warnings, b.provider, b.model,
            (SELECT count(*) FROM content_posts x WHERE x.plan_id = p.id) AS post_count,
            (SELECT count(*) FROM content_posts x JOIN content_drafts d ON d.id = x.draft_id
              WHERE x.plan_id = p.id AND d.status = 'DRAFT' AND x.posted_on IS NULL) AS proposed_count,
            (SELECT count(*) FROM content_posts x JOIN content_drafts d ON d.id = x.draft_id
              WHERE x.plan_id = p.id AND d.status = 'APPROVED' AND x.posted_on IS NULL) AS approved_count,
            (SELECT count(*) FROM content_posts x WHERE x.plan_id = p.id AND x.posted_on IS NOT NULL) AS posted_count""";
    private static final String PLAN_FROM = """
             FROM content_plans p
             JOIN photo_sessions s ON s.workspace_id = p.workspace_id AND s.id = p.session_id
             JOIN content_batches b ON b.workspace_id = p.workspace_id AND b.id = p.batch_id
            """;
    /** Posts a person has not changed or approved, which "Refresh plan" replaces. */
    private static final String UNTOUCHED = """
            x.posted_on IS NULL AND NOT x.touched AND NOT x.regenerating AND d.status = 'DRAFT'""";

    private final JdbcClient jdbc;
    private final ContentService content;
    private final ContentPostService posts;
    private final AssetService assets;
    private final PhotoSessionService sessions;
    private final StudioSettingsService settings;
    private final WorkspaceService workspaces;
    private final ContentProperties properties;

    ContentPlanService(JdbcClient jdbc, ContentService content, ContentPostService posts, AssetService assets,
            PhotoSessionService sessions, StudioSettingsService settings, WorkspaceService workspaces,
            ContentProperties properties) {
        this.jdbc = jdbc;
        this.content = content;
        this.posts = posts;
        this.assets = assets;
        this.sessions = sessions;
        this.settings = settings;
        this.workspaces = workspaces;
        this.properties = properties;
    }

    /**
     * Creates the session's plan, or refreshes it: a new generation that replaces the untouched proposals and keeps
     * every post a person edited, approved, or posted. Asking while a generation is pending changes nothing.
     */
    @Transactional
    PlanDetail request(WorkspaceContext workspace, UUID sessionId) {
        SessionSummary session = sessions.findSummary(workspace, sessionId).orElseThrow(NotFoundException::new);
        if (session.archived()) {
            throw new ConflictException("This session is archived. Restore it before planning content for it.");
        }
        UUID planId = insertPlan(workspace.workspaceId(), sessionId, PlanOrigin.MANUAL)
                .orElseGet(() -> lockPlan(workspace.workspaceId(), sessionId));
        PlanView plan = findBySession(workspace.workspaceId(), sessionId).orElse(null);
        if (plan != null && plan.status() == ContentBatchStatus.PENDING) {
            return get(workspace, sessionId);
        }
        switch (start(workspace.workspaceId(), sessionId, session.sessionType(), planId, workspace.userId())) {
            case NO_PHOTOS -> throw new ConflictException(NO_PHOTOS);
            case ALL_USED -> throw new ConflictException(ALL_PHOTOS_USED);
            case STARTED -> {}
        }
        return get(workspace, sessionId);
    }

    /** The session's plan with its posts in calendar order. */
    @Transactional(readOnly = true)
    PlanDetail get(WorkspaceContext workspace, UUID sessionId) {
        PlanView plan = findBySession(workspace.workspaceId(), sessionId).orElseThrow(NotFoundException::new);
        return new PlanDetail(plan, posts.listForPlan(workspace.workspaceId(), plan.id()));
    }

    /**
     * Plans, most recently changed first. {@code toReview} keeps plans that are generating, failed, or have posts
     * waiting for approval.
     */
    @Transactional(readOnly = true)
    PageView<PlanView> list(WorkspaceContext workspace, boolean toReview, PageQuery page) {
        String where = " WHERE p.workspace_id = :workspaceId" + (toReview ? " AND " + """
                 (b.status <> 'SUCCEEDED' OR EXISTS (
                     SELECT 1 FROM content_posts x JOIN content_drafts d ON d.id = x.draft_id
                      WHERE x.plan_id = p.id AND d.status = 'DRAFT' AND x.posted_on IS NULL))""" : "");
        long total = jdbc.sql("SELECT count(*)" + PLAN_FROM + where)
                .param("workspaceId", workspace.workspaceId())
                .query(Long.class)
                .single();
        List<PlanView> rows = jdbc.sql("SELECT " + PLAN_COLUMNS + PLAN_FROM + where
                        + " ORDER BY p.updated_at DESC, p.id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(ContentPlanService::planView)
                .list();
        return PageView.of(rows, page, total);
    }

    /** Runs a failed generation again. Retrying one that is pending or done changes nothing. */
    @Transactional
    PlanDetail retry(WorkspaceContext workspace, UUID planId) {
        UUID sessionId = jdbc.sql("SELECT session_id FROM content_plans WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", planId)
                .param("workspaceId", workspace.workspaceId())
                .query(UUID.class)
                .optional()
                .orElseThrow(NotFoundException::new);
        PlanView plan = findBySession(workspace.workspaceId(), sessionId).orElseThrow(NotFoundException::new);
        if (plan.status() == ContentBatchStatus.FAILED) {
            content.retry(workspace, plan.batchId());
        }
        return get(workspace, sessionId);
    }

    /** The plan's batch and session, for adding a post a person wrote. */
    @Transactional(readOnly = true)
    PlanView find(WorkspaceContext workspace, UUID planId) {
        return jdbc.sql("SELECT " + PLAN_COLUMNS + PLAN_FROM + " WHERE p.id = :id AND p.workspace_id = :workspaceId")
                .param("id", planId)
                .param("workspaceId", workspace.workspaceId())
                .query(ContentPlanService::planView)
                .optional()
                .orElseThrow(NotFoundException::new);
    }

    // --- Automatic proposals and gallery publication, without a signed-in user. ---

    /**
     * Proposes a plan for a session whose photos have settled, unless it already has one, it is archived, or the
     * workspace turned automatic plans off. Safe to call more than once and from several instances at once: only
     * the call that creates the plan row starts a generation. Returns true if it started one.
     */
    @Transactional
    boolean proposeAutomatically(UUID workspaceId, UUID sessionId) {
        Optional<String> sessionType = jdbc.sql("""
                        SELECT coalesce(s.session_type, '') FROM photo_sessions s
                         WHERE s.workspace_id = :workspaceId AND s.id = :sessionId AND s.archived_at IS NULL""")
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .query(String.class)
                .optional();
        if (sessionType.isEmpty() || !settings.forWorkspace(workspaceId).autoPlans()) {
            return false;
        }
        Optional<UUID> planId = insertPlan(workspaceId, sessionId, PlanOrigin.AUTOMATIC);
        if (planId.isEmpty()) {
            return false;
        }
        StartResult result = start(workspaceId, sessionId, sessionType.get().isEmpty() ? null : sessionType.get(),
                planId.get(), null);
        if (result != StartResult.STARTED) {
            throw new IllegalStateException("A settled session had no ready photos"); // Rolls the plan row back.
        }
        return true;
    }

    /**
     * Fixes the delivery and drip dates when a session's gallery is first published. Runs in the transaction of
     * the change; a gallery published again later changes nothing.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onGalleryStatusChanged(GalleryStatusChanged event) {
        if (event.to() != GalleryStatus.PUBLISHED) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(event.occurredAt(), workspaces.zone(event.workspaceId()));
        Optional<UUID> planId = jdbc.sql("""
                        UPDATE content_plans SET published_on = :today, updated_at = now()
                         WHERE workspace_id = :workspaceId AND session_id = :sessionId AND published_on IS NULL
                        RETURNING id
                        """)
                .param("today", today)
                .param("workspaceId", event.workspaceId())
                .param("sessionId", event.sessionId())
                .query(UUID.class)
                .optional();
        planId.ifPresent(id -> anchorPosts(event.workspaceId(), id, today));
    }

    // --- Used by GeneratePlanJob. ---

    /** The plan a pending batch generates for, with what the job needs. Empty if the plan moved on to a newer batch. */
    Optional<PlanForJob> findForJob(UUID workspaceId, UUID batchId) {
        return jdbc.sql("""
                        SELECT p.id, p.session_id FROM content_plans p
                         WHERE p.workspace_id = :workspaceId AND p.batch_id = :batchId""")
                .param("workspaceId", workspaceId)
                .param("batchId", batchId)
                .query((rs, n) -> new PlanForJob(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class)))
                .optional();
    }

    /**
     * The template's slots that the plan still needs: a slot filled by a post a person kept is left out, so a
     * refresh never writes a second sneak peek after the first was approved. Slots are renumbered from 1.
     */
    List<PlannedSlot> openSlots(UUID workspaceId, UUID planId, StudioSettings studio) {
        List<String> kept = jdbc.sql("""
                        SELECT x.stage || ':' || CASE WHEN x.format IN ('SINGLE', 'CAROUSEL') THEN 'FEED' ELSE x.format END
                          FROM content_posts x JOIN content_drafts d ON d.id = x.draft_id
                         WHERE x.workspace_id = :workspaceId AND x.plan_id = :planId AND NOT (""" + UNTOUCHED + ")")
                .param("workspaceId", workspaceId)
                .param("planId", planId)
                .query(String.class)
                .list();
        List<String> remaining = new ArrayList<>(kept);
        List<PlannedSlot> open = new ArrayList<>();
        for (PlannedSlot planned : PlanTemplate.slots(studio)) {
            if (remaining.remove(planned.slot().stage() + ":" + planned.slot().format())) {
                continue;
            }
            PlanRequest.Slot slot = planned.slot();
            open.add(new PlannedSlot(new PlanRequest.Slot(open.size() + 1, slot.stage(), slot.format(), slot.minPhotos(),
                    slot.maxPhotos()), planned.offsetDays()));
        }
        return open;
    }

    /**
     * Stores a validated plan: ends the batch, replaces the plan's untouched proposals with the new posts, and dates
     * them. {@code sentPhotos} are the photos the provider received, in request order; posts point into it. Returns
     * false if the batch was no longer pending or the plan moved on, in which case nothing changes.
     */
    @Transactional
    boolean complete(UUID workspaceId, UUID batchId, UUID planId, List<UUID> sentPhotos, List<PlannedSlot> slots,
            ValidatedPlan plan, String provider, String model, Integer inputTokens, Integer outputTokens) {
        Optional<PlanLock> locked = jdbc.sql("""
                        SELECT session_id, published_on FROM content_plans
                         WHERE id = :id AND workspace_id = :workspaceId AND batch_id = :batchId FOR UPDATE""")
                .param("id", planId)
                .param("workspaceId", workspaceId)
                .param("batchId", batchId)
                .query((rs, n) -> new PlanLock(rs.getObject("session_id", UUID.class),
                        rs.getObject("published_on", LocalDate.class)))
                .optional();
        if (locked.isEmpty() || !content.completePlanBatch(workspaceId, batchId, provider, model, inputTokens,
                outputTokens, plan.warnings())) {
            return false;
        }
        UUID sessionId = locked.get().sessionId();
        LocalDate today = LocalDate.now(workspaces.zone(workspaceId));
        LocalDate publishedOn = locked.get().publishedOn();
        if (publishedOn == null && galleryPublished(workspaceId, sessionId)) {
            publishedOn = today; // Published before the plan existed: deliver from today.
            jdbc.sql("UPDATE content_plans SET published_on = :today WHERE id = :id AND workspace_id = :workspaceId")
                    .param("today", today)
                    .param("id", planId)
                    .param("workspaceId", workspaceId)
                    .update();
        }
        jdbc.sql("""
                        DELETE FROM content_drafts WHERE workspace_id = :workspaceId AND id IN (
                            SELECT x.draft_id FROM content_posts x JOIN content_drafts d ON d.id = x.draft_id
                             WHERE x.workspace_id = :workspaceId AND x.plan_id = :planId AND (""" + UNTOUCHED + "))")
                .param("workspaceId", workspaceId)
                .param("planId", planId)
                .update();
        for (PlannedPost post : plan.posts()) {
            int offset = slots.get(post.slot().number() - 1).offsetDays();
            LocalDate plannedOn = post.slot().stage() == PostStage.SNEAK_PEEK
                    ? today.plusDays(offset)
                    : publishedOn == null ? null : publishedOn.plusDays(offset);
            UUID draftId = content.insertDraft(workspaceId, batchId, post.text(), null);
            UUID postId = jdbc.sql("""
                            INSERT INTO content_posts (workspace_id, session_id, plan_id, draft_id, stage, format, idea,
                                                       offset_days, planned_on)
                            VALUES (:workspaceId, :sessionId, :planId, :draftId, :stage, :format, :idea, :offset,
                                    :plannedOn)
                            RETURNING id
                            """)
                    .param("workspaceId", workspaceId)
                    .param("sessionId", sessionId)
                    .param("planId", planId)
                    .param("draftId", draftId)
                    .param("stage", post.slot().stage().name())
                    .param("format", post.format().name())
                    .param("idea", post.idea())
                    .param("offset", offset)
                    .param("plannedOn", plannedOn)
                    .query(UUID.class)
                    .single();
            posts.insertPhotos(workspaceId, sessionId, postId,
                    post.photos().stream().map(index -> new PostPhotoInput(sentPhotos.get(index), null)).toList());
        }
        jdbc.sql("UPDATE content_plans SET updated_at = now() WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", planId)
                .param("workspaceId", workspaceId)
                .update();
        return true;
    }

    // --- Internals. ---

    /** Inserts the session's plan row unless it exists; the ID only if this call created it. */
    private Optional<UUID> insertPlan(UUID workspaceId, UUID sessionId, PlanOrigin origin) {
        return jdbc.sql("""
                        INSERT INTO content_plans (workspace_id, session_id, origin)
                        VALUES (:workspaceId, :sessionId, :origin)
                        ON CONFLICT (workspace_id, session_id) DO NOTHING
                        RETURNING id
                        """)
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .param("origin", origin.name())
                .query(UUID.class)
                .optional();
    }

    private UUID lockPlan(UUID workspaceId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT id FROM content_plans WHERE workspace_id = :workspaceId AND session_id = :sessionId
                           FOR UPDATE""")
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .query(UUID.class)
                .single();
    }

    private enum StartResult {
        STARTED,
        NO_PHOTOS,
        ALL_USED
    }

    /**
     * Starts a generation: a {@code PLAN} batch with the session's ready photos that no kept post uses, spread over
     * the session up to the limit, and its job. Must run in the transaction that holds the plan row.
     */
    private StartResult start(UUID workspaceId, UUID sessionId, String sessionType, UUID planId, UUID userId) {
        List<AssetService.ReadyPhoto> ready = assets.readyPhotos(workspaceId, sessionId);
        if (ready.isEmpty()) {
            return StartResult.NO_PHOTOS;
        }
        Set<UUID> kept = new HashSet<>(jdbc.sql("""
                        SELECT ph.asset_id FROM content_post_photos ph
                          JOIN content_posts x ON x.id = ph.post_id
                          JOIN content_drafts d ON d.id = x.draft_id
                         WHERE x.workspace_id = :workspaceId AND x.plan_id = :planId
                           AND x.format IN ('SINGLE', 'CAROUSEL') AND NOT (""" + UNTOUCHED + ")")
                .param("workspaceId", workspaceId)
                .param("planId", planId)
                .query(UUID.class)
                .list());
        List<UUID> candidates = ready.stream().map(AssetService.ReadyPhoto::id).filter(id -> !kept.contains(id)).toList();
        if (candidates.isEmpty()) {
            return StartResult.ALL_USED;
        }
        List<UUID> photos = spread(candidates, properties.plans().maxPhotos());
        UUID batchId = jdbc.sql("""
                        INSERT INTO content_batches (workspace_id, session_id, kind, status, session_type, caption_count,
                                                     include_hashtags, include_call_to_action, created_by)
                        VALUES (:workspaceId, :sessionId, 'PLAN', 'PENDING', :sessionType, :postCount, true, true,
                                :userId)
                        RETURNING id
                        """)
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .param("sessionType", sessionType)
                .param("postCount", Math.max(1, PlanTemplate.slots(settings.forWorkspace(workspaceId)).size()))
                .param("userId", userId)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO content_batch_assets (batch_id, asset_id, workspace_id, session_id, position)
                        SELECT :id, a.asset_id, :workspaceId, :sessionId, a.ordinal - 1
                          FROM unnest(CAST(:assetIds AS uuid[])) WITH ORDINALITY AS a (asset_id, ordinal)
                        """)
                .param("id", batchId)
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .param("assetIds", photos.toArray(UUID[]::new))
                .update();
        jdbc.sql("""
                        UPDATE content_plans SET batch_id = :batchId, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("batchId", batchId)
                .param("id", planId)
                .param("workspaceId", workspaceId)
                .update();
        content.enqueue(workspaceId, ContentBatchKind.PLAN, batchId, 1);
        log.info("Session {}: plan generation queued in batch {} with {} photo(s)", sessionId, batchId, photos.size());
        return StartResult.STARTED;
    }

    /** Up to {@code max} items spread evenly from first to last, in order, so a plan sees the whole session. */
    static <T> List<T> spread(List<T> items, int max) {
        if (items.size() <= max) {
            return items;
        }
        List<T> picked = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            picked.add(items.get((int) Math.round(i * (items.size() - 1) / (double) (max - 1))));
        }
        return picked;
    }

    private void anchorPosts(UUID workspaceId, UUID planId, LocalDate publishedOn) {
        jdbc.sql("""
                        UPDATE content_posts
                           SET planned_on = CAST(:publishedOn AS date) + offset_days, version = version + 1,
                               updated_at = now()
                         WHERE workspace_id = :workspaceId AND plan_id = :planId AND stage <> 'SNEAK_PEEK'
                           AND planned_on IS NULL AND posted_on IS NULL
                        """)
                .param("publishedOn", publishedOn)
                .param("workspaceId", workspaceId)
                .param("planId", planId)
                .update();
    }

    private boolean galleryPublished(UUID workspaceId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM galleries g
                                        WHERE g.workspace_id = :workspaceId AND g.session_id = :sessionId
                                          AND g.status = 'PUBLISHED' AND g.archived_at IS NULL)""")
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .single();
    }

    private Optional<PlanView> findBySession(UUID workspaceId, UUID sessionId) {
        return jdbc.sql("SELECT " + PLAN_COLUMNS + PLAN_FROM
                        + " WHERE p.workspace_id = :workspaceId AND p.session_id = :sessionId")
                .param("workspaceId", workspaceId)
                .param("sessionId", sessionId)
                .query(ContentPlanService::planView)
                .optional();
    }

    private static PlanView planView(ResultSet rs, int rowNum) throws SQLException {
        Array warnings = rs.getArray("warnings");
        return new PlanView(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("session_title"),
                PlanOrigin.valueOf(rs.getString("origin")),
                rs.getObject("batch_id", UUID.class),
                ContentBatchStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                warnings == null ? List.of() : List.of((String[]) warnings.getArray()),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getObject("published_on", LocalDate.class),
                rs.getInt("post_count"),
                rs.getInt("proposed_count"),
                rs.getInt("approved_count"),
                rs.getInt("posted_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private record PlanLock(UUID sessionId, LocalDate publishedOn) {}

    /** A plan as its generation job sees it. */
    record PlanForJob(UUID id, UUID sessionId) {}

    enum PlanOrigin {
        AUTOMATIC,
        MANUAL
    }

    /**
     * A plan as lists show it. {@code status} and {@code failureReason} are those of its latest generation;
     * {@code publishedOn} is the day the session's gallery was first published, if it has been.
     */
    record PlanView(
            UUID id,
            UUID sessionId,
            String sessionTitle,
            PlanOrigin origin,
            UUID batchId,
            ContentBatchStatus status,
            String failureReason,
            List<String> warnings,
            String provider,
            String model,
            LocalDate publishedOn,
            int postCount,
            int proposedCount,
            int approvedCount,
            int postedCount,
            Instant createdAt,
            Instant updatedAt) {}

    record PlanDetail(PlanView plan, List<PostView> posts) {}
}
