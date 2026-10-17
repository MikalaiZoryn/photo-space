package com.photospace.content;

import com.photospace.assets.AssetService;
import com.photospace.assets.AssetService.AssetView;
import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.content.ContentValidator.ValidatedContent;
import com.photospace.jobs.JobService;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Generation batches and their caption drafts. Every query is scoped by the verified workspace. A batch is
 * created with its photos and a queued job in one transaction; {@link GenerateContentJob} fills it through the
 * package-private methods at the bottom, which act only on a {@code PENDING} batch. Drafts change only through a
 * person: each edit is a new version, and approval names the version it applies to.
 */
@Service
class ContentService {

    private static final String BATCH_COLUMNS = """
            b.id, b.workspace_id, b.session_id, s.title AS session_title, b.status, b.session_type, b.notes,
            b.caption_count, b.include_hashtags, b.include_call_to_action, b.run, b.failure_reason, b.provider,
            b.model, b.prompt_version, b.carousel_rationale, b.story_ideas, b.warnings, b.created_at,
            b.completed_at,
            (SELECT count(*) FROM content_batch_assets x WHERE x.batch_id = b.id) AS photo_count,
            (SELECT count(*) FROM content_drafts d WHERE d.batch_id = b.id) AS draft_count,
            (SELECT count(*) FROM content_drafts d WHERE d.batch_id = b.id AND d.status = 'APPROVED') AS approved_count
            """;
    private static final String BATCH_FROM = """
             FROM content_batches b
             JOIN photo_sessions s ON s.workspace_id = b.workspace_id AND s.id = b.session_id
            """;
    private static final String DRAFT_COLUMNS = """
            d.id, d.batch_id, d.position, d.status, d.version, d.approved_version, d.approved_at, d.updated_at,
            v.caption, v.call_to_action, v.hashtags, v.source, b.notes AS batch_notes""";
    private static final String DRAFT_FROM = """
             FROM content_drafts d
             JOIN content_draft_versions v ON v.draft_id = d.id AND v.version = d.version
             JOIN content_batches b ON b.workspace_id = d.workspace_id AND b.id = d.batch_id
            """;

    private final JdbcClient jdbc;
    private final PhotoSessionService sessions;
    private final AssetService assets;
    private final JobService jobs;
    private final BrandProfileService brands;
    private final ContentProperties properties;
    private final AuditService audit;

    ContentService(JdbcClient jdbc, PhotoSessionService sessions, AssetService assets, JobService jobs,
            BrandProfileService brands, ContentProperties properties, AuditService audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.assets = assets;
        this.jobs = jobs;
        this.brands = brands;
        this.properties = properties;
        this.audit = audit;
    }

    /** Records the request and queues its generation. Nothing is sent to the provider in this request. */
    @Transactional
    BatchDetail create(WorkspaceContext workspace, BatchInput input) {
        SessionSummary session = sessions.findSummary(workspace, input.sessionId())
                .orElseThrow(() -> InvalidFieldsException.of("sessionId", "does not match a session in this workspace"));
        if (session.archived()) {
            throw new ConflictException("This session is archived. Restore it before creating content for it.");
        }
        LinkedHashSet<UUID> photos = new LinkedHashSet<>(input.assetIds());
        if (photos.isEmpty()) {
            throw InvalidFieldsException.of("assetIds", "must contain at least one photo");
        }
        if (photos.size() != input.assetIds().size()) {
            throw InvalidFieldsException.of("assetIds", "must not contain the same photo twice");
        }
        if (photos.size() > properties.maxPhotos()) {
            throw InvalidFieldsException.of("assetIds", "must have at most " + properties.maxPhotos() + " photos");
        }
        if (assets.findReady(workspace.workspaceId(), session.id(), photos).size() != photos.size()) {
            throw InvalidFieldsException.of("assetIds", "must be ready photos of this session");
        }
        UUID id = jdbc.sql("""
                        INSERT INTO content_batches (workspace_id, session_id, status, session_type, notes, caption_count,
                                                     include_hashtags, include_call_to_action, created_by)
                        VALUES (:workspaceId, :sessionId, 'PENDING', :sessionType, :notes, :captionCount,
                                :includeHashtags, :includeCallToAction, :userId)
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", session.id())
                .param("sessionType", session.sessionType())
                .param("notes", input.notes())
                .param("captionCount", input.captionCount())
                .param("includeHashtags", input.includeHashtags())
                .param("includeCallToAction", input.includeCallToAction())
                .param("userId", workspace.userId())
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO content_batch_assets (batch_id, asset_id, workspace_id, session_id, position)
                        SELECT :id, a.asset_id, :workspaceId, :sessionId, a.ordinal - 1
                          FROM unnest(CAST(:assetIds AS uuid[])) WITH ORDINALITY AS a (asset_id, ordinal)
                        """)
                .param("id", id)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", session.id())
                .param("assetIds", photos.toArray(UUID[]::new))
                .update();
        jobs.enqueue(workspace.workspaceId(), GenerateContentJob.TYPE, id, jobKey(id, 1));
        return get(workspace, id);
    }

    /** Batches, newest first, optionally of one session. */
    @Transactional(readOnly = true)
    PageView<BatchView> list(WorkspaceContext workspace, UUID sessionId, PageQuery page) {
        String where = " WHERE b.workspace_id = :workspaceId" + (sessionId == null ? "" : " AND b.session_id = :sessionId");
        long total = jdbc.sql("SELECT count(*) FROM content_batches b" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
        List<BatchView> rows = jdbc.sql("SELECT " + BATCH_COLUMNS + BATCH_FROM + where
                        + " ORDER BY b.created_at DESC, b.id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("sessionId", sessionId)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(ContentService::batchView)
                .list();
        return PageView.of(rows, page, total);
    }

    /** The batch with its photos (in selection order), the suggested carousel order, and its drafts. */
    @Transactional(readOnly = true)
    BatchDetail get(WorkspaceContext workspace, UUID batchId) {
        BatchView batch = findBatch(workspace.workspaceId(), batchId).orElseThrow(NotFoundException::new);
        List<BatchPhoto> rows = jdbc.sql("""
                        SELECT asset_id, carousel_position FROM content_batch_assets
                         WHERE batch_id = :id AND workspace_id = :workspaceId ORDER BY position""")
                .param("id", batchId)
                .param("workspaceId", workspace.workspaceId())
                .query((rs, n) -> new BatchPhoto(rs.getObject("asset_id", UUID.class),
                        rs.getObject("carousel_position", Integer.class)))
                .list();
        Map<UUID, AssetView> ready = assets.findReady(workspace.workspaceId(), batch.sessionId(),
                rows.stream().map(BatchPhoto::assetId).toList());
        List<AssetView> photos = rows.stream().map(row -> ready.get(row.assetId())).filter(Objects::nonNull).toList();
        List<UUID> carousel = rows.stream()
                .filter(row -> row.carouselPosition() != null && ready.containsKey(row.assetId()))
                .sorted((a, b) -> Integer.compare(a.carouselPosition(), b.carouselPosition()))
                .map(BatchPhoto::assetId)
                .toList();
        List<String> brandText = brandText(workspace.workspaceId());
        List<DraftView> drafts = jdbc.sql("SELECT " + DRAFT_COLUMNS + DRAFT_FROM
                        + " WHERE d.workspace_id = :workspaceId AND d.batch_id = :batchId ORDER BY d.position")
                .param("workspaceId", workspace.workspaceId())
                .param("batchId", batchId)
                .query((rs, n) -> draftView(rs, brandText))
                .list();
        return new BatchDetail(batch, photos, carousel, drafts);
    }

    /** Runs a failed batch again with the same input. Retrying a batch that is still pending changes nothing. */
    @Transactional
    BatchDetail retry(WorkspaceContext workspace, UUID batchId) {
        BatchView batch = lockBatch(workspace.workspaceId(), batchId);
        if (batch.status() == ContentBatchStatus.SUCCEEDED) {
            throw new ConflictException("This batch already has drafts. Use Regenerate for new ones.");
        }
        if (batch.status() == ContentBatchStatus.FAILED) {
            jdbc.sql("""
                            UPDATE content_batches
                               SET status = 'PENDING', run = run + 1, failure_reason = NULL, completed_at = NULL
                             WHERE id = :id AND workspace_id = :workspaceId
                            """)
                    .param("id", batchId)
                    .param("workspaceId", workspace.workspaceId())
                    .update();
            jobs.enqueue(workspace.workspaceId(), GenerateContentJob.TYPE, batchId, jobKey(batchId, batch.run() + 1));
        }
        return get(workspace, batchId);
    }

    /**
     * A new batch with the same session, photos, notes, and options, generated with the current brand profile.
     * The earlier batch and its drafts stay as they are.
     */
    @Transactional
    BatchDetail regenerate(WorkspaceContext workspace, UUID batchId) {
        BatchView batch = findBatch(workspace.workspaceId(), batchId).orElseThrow(NotFoundException::new);
        List<UUID> assetIds = jdbc.sql("""
                        SELECT asset_id FROM content_batch_assets
                         WHERE batch_id = :id AND workspace_id = :workspaceId ORDER BY position""")
                .param("id", batchId)
                .param("workspaceId", workspace.workspaceId())
                .query(UUID.class)
                .list();
        return create(workspace, new BatchInput(batch.sessionId(), assetIds, batch.notes(), batch.captionCount(),
                batch.includeHashtags(), batch.includeCallToAction()));
    }

    /** One draft with every version, newest first. */
    @Transactional(readOnly = true)
    DraftDetail getDraft(WorkspaceContext workspace, UUID draftId) {
        DraftView draft = findDraft(workspace.workspaceId(), draftId, false).orElseThrow(NotFoundException::new);
        List<DraftVersionView> versions = jdbc.sql("""
                        SELECT version, caption, call_to_action, hashtags, source, created_at
                          FROM content_draft_versions
                         WHERE draft_id = :id AND workspace_id = :workspaceId ORDER BY version DESC""")
                .param("id", draftId)
                .param("workspaceId", workspace.workspaceId())
                .query((rs, n) -> new DraftVersionView(
                        rs.getInt("version"),
                        rs.getString("caption"),
                        rs.getString("call_to_action"),
                        strings(rs.getArray("hashtags")),
                        DraftSource.valueOf(rs.getString("source")),
                        instant(rs, "created_at")))
                .list();
        return new DraftDetail(draft, versions);
    }

    /**
     * Saves an edit as a new version. An approved draft becomes a draft again, because the approval was for the
     * earlier text. Saving the same text again changes nothing.
     */
    @Transactional
    DraftView updateDraft(WorkspaceContext workspace, UUID draftId, int version, DraftInput input) {
        DraftView current = lockDraft(workspace, draftId, version);
        DraftText text = checked(input);
        if (text.equals(new DraftText(current.caption(), current.callToAction(), current.hashtags()))) {
            return current;
        }
        int next = current.version() + 1;
        jdbc.sql("""
                        INSERT INTO content_draft_versions (draft_id, workspace_id, version, caption, call_to_action,
                                                            hashtags, source, created_by)
                        VALUES (:draftId, :workspaceId, :version, :caption, :callToAction, CAST(:hashtags AS text[]),
                                'EDITED', :userId)
                        """)
                .param("draftId", draftId)
                .param("workspaceId", workspace.workspaceId())
                .param("version", next)
                .param("caption", text.caption())
                .param("callToAction", text.callToAction())
                .param("hashtags", text.hashtags().toArray(String[]::new))
                .param("userId", workspace.userId())
                .update();
        jdbc.sql("""
                        UPDATE content_drafts
                           SET version = :version, status = 'DRAFT', approved_version = NULL, approved_by = NULL,
                               approved_at = NULL, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("version", next)
                .param("id", draftId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        return findDraft(workspace.workspaceId(), draftId, false).orElseThrow();
    }

    /**
     * Approves the draft's current version, or takes the approval back. {@code version} must be the version the
     * person reviewed, so an edit made meanwhile by someone else is never approved unseen.
     */
    @Transactional
    DraftView changeDraftStatus(WorkspaceContext workspace, UUID draftId, int version, DraftStatus target) {
        DraftView current = lockDraft(workspace, draftId, version);
        if (current.status() == target) {
            return current;
        }
        boolean approve = target == DraftStatus.APPROVED;
        jdbc.sql("""
                        UPDATE content_drafts
                           SET status = :status,
                               approved_version = CASE WHEN :approve THEN version END,
                               approved_by = CASE WHEN :approve THEN CAST(:userId AS uuid) END,
                               approved_at = CASE WHEN :approve THEN now() END,
                               updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", target.name())
                .param("approve", approve)
                .param("userId", workspace.userId())
                .param("id", draftId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, approve ? AuditAction.CONTENT_DRAFT_APPROVED : AuditAction.CONTENT_DRAFT_UNAPPROVED,
                AuditTarget.CONTENT_DRAFT, draftId, "version " + current.version());
        return findDraft(workspace.workspaceId(), draftId, false).orElseThrow();
    }

    // --- Used by GenerateContentJob, which runs without a signed-in user and only moves a PENDING batch on. ---

    Optional<PendingBatch> findPending(UUID workspaceId, UUID batchId) {
        return findBatch(workspaceId, batchId)
                .filter(batch -> batch.status() == ContentBatchStatus.PENDING)
                .map(batch -> new PendingBatch(
                        batch.id(),
                        workspaceId,
                        batch.sessionId(),
                        batch.sessionType(),
                        batch.notes(),
                        batch.captionCount(),
                        batch.includeHashtags(),
                        batch.includeCallToAction(),
                        jdbc.sql("""
                                        SELECT asset_id FROM content_batch_assets
                                         WHERE batch_id = :id AND workspace_id = :workspaceId ORDER BY position""")
                                .param("id", batchId)
                                .param("workspaceId", workspaceId)
                                .query(UUID.class)
                                .list()));
    }

    /**
     * Stores the validated drafts and suggestions. {@code sentPhotos} are the photos the provider received, in
     * request order; the carousel order points into it. Returns false if the batch was no longer pending.
     */
    @Transactional
    boolean complete(PendingBatch batch, List<UUID> sentPhotos, ValidatedContent content, String provider,
            String model, Integer inputTokens, Integer outputTokens) {
        int updated = jdbc.sql("""
                        UPDATE content_batches
                           SET status = 'SUCCEEDED', failure_reason = NULL, provider = :provider, model = :model,
                               prompt_version = :promptVersion, input_tokens = :inputTokens,
                               output_tokens = :outputTokens, carousel_rationale = :rationale,
                               story_ideas = CAST(:storyIdeas AS text[]), warnings = CAST(:warnings AS text[]),
                               completed_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                        """)
                .param("provider", provider)
                .param("model", model)
                .param("promptVersion", ContentPrompt.VERSION)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .param("rationale", content.carouselRationale())
                .param("storyIdeas", content.storyIdeas().toArray(String[]::new))
                .param("warnings", content.warnings().toArray(String[]::new))
                .param("id", batch.id())
                .param("workspaceId", batch.workspaceId())
                .update();
        if (updated == 0) {
            return false;
        }
        List<Integer> order = content.carouselOrder();
        for (int position = 0; position < order.size(); position++) {
            jdbc.sql("""
                            UPDATE content_batch_assets SET carousel_position = :position
                             WHERE batch_id = :id AND workspace_id = :workspaceId AND asset_id = :assetId""")
                    .param("position", position)
                    .param("id", batch.id())
                    .param("workspaceId", batch.workspaceId())
                    .param("assetId", sentPhotos.get(order.get(position)))
                    .update();
        }
        for (int position = 0; position < content.captions().size(); position++) {
            DraftText text = content.captions().get(position);
            UUID draftId = jdbc.sql("""
                            INSERT INTO content_drafts (workspace_id, batch_id, position, status, version)
                            VALUES (:workspaceId, :batchId, :position, 'DRAFT', 1)
                            RETURNING id
                            """)
                    .param("workspaceId", batch.workspaceId())
                    .param("batchId", batch.id())
                    .param("position", position)
                    .query(UUID.class)
                    .single();
            jdbc.sql("""
                            INSERT INTO content_draft_versions (draft_id, workspace_id, version, caption,
                                                                call_to_action, hashtags, source)
                            VALUES (:draftId, :workspaceId, 1, :caption, :callToAction, CAST(:hashtags AS text[]),
                                    'GENERATED')
                            """)
                    .param("draftId", draftId)
                    .param("workspaceId", batch.workspaceId())
                    .param("caption", text.caption())
                    .param("callToAction", text.callToAction())
                    .param("hashtags", text.hashtags().toArray(String[]::new))
                    .update();
        }
        return true;
    }

    /** Ends a pending batch as failed. {@code reason} must be a fixed, user-facing sentence. */
    @Transactional
    void fail(UUID workspaceId, UUID batchId, String reason) {
        jdbc.sql("""
                        UPDATE content_batches SET status = 'FAILED', failure_reason = :reason, completed_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                        """)
                .param("reason", reason)
                .param("id", batchId)
                .param("workspaceId", workspaceId)
                .update();
    }

    /** Ends a pending batch whose retries ran out, keeping the last problem recorded if there was one. */
    @Transactional
    void failAfterRetries(UUID workspaceId, UUID batchId, String fallbackReason) {
        jdbc.sql("""
                        UPDATE content_batches
                           SET status = 'FAILED', failure_reason = coalesce(failure_reason, :reason), completed_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                        """)
                .param("reason", fallbackReason)
                .param("id", batchId)
                .param("workspaceId", workspaceId)
                .update();
    }

    /** Records why the current attempt failed while the batch waits for an automatic retry. */
    @Transactional
    void recordProblem(UUID workspaceId, UUID batchId, String reason) {
        jdbc.sql("""
                        UPDATE content_batches SET failure_reason = :reason
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                        """)
                .param("reason", reason)
                .param("id", batchId)
                .param("workspaceId", workspaceId)
                .update();
    }

    // --- Internals. ---

    private static String jobKey(UUID batchId, int run) {
        return GenerateContentJob.TYPE + ":" + batchId + ":" + run;
    }

    /** Checks what annotations cannot: hashtag characters, repeats, and the length of the whole post. */
    private static DraftText checked(DraftInput input) {
        List<String> hashtags = new ArrayList<>();
        for (String tag : input.hashtags()) {
            if (!Hashtags.valid(tag)) {
                throw InvalidFieldsException.of("hashtags", "must be letters, digits, or underscores, without spaces");
            }
            if (hashtags.stream().noneMatch(tag::equalsIgnoreCase)) {
                hashtags.add(tag);
            }
        }
        DraftText text = new DraftText(input.caption(), input.callToAction(), hashtags);
        if (text.postLength() > ContentPrompt.MAX_CAPTION_CHARS) {
            throw InvalidFieldsException.of("caption", "with the call to action and hashtags, must be at most "
                    + ContentPrompt.MAX_CAPTION_CHARS + " characters");
        }
        return text;
    }

    /** The text the photographer wrote themselves, which review hints compare drafts against. */
    private List<String> brandText(UUID workspaceId) {
        GenerationRequest.Brand brand = brands.forGeneration(workspaceId);
        return Arrays.asList(brand.voice(), brand.audience(), brand.preferences(), brand.avoid());
    }

    private Optional<BatchView> findBatch(UUID workspaceId, UUID batchId) {
        return jdbc.sql("SELECT " + BATCH_COLUMNS + BATCH_FROM + " WHERE b.id = :id AND b.workspace_id = :workspaceId")
                .param("id", batchId)
                .param("workspaceId", workspaceId)
                .query(ContentService::batchView)
                .optional();
    }

    private BatchView lockBatch(UUID workspaceId, UUID batchId) {
        jdbc.sql("SELECT id FROM content_batches WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE")
                .param("id", batchId)
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .optional()
                .orElseThrow(NotFoundException::new);
        return findBatch(workspaceId, batchId).orElseThrow();
    }

    private Optional<DraftView> findDraft(UUID workspaceId, UUID draftId, boolean forUpdate) {
        if (forUpdate) {
            Optional<UUID> locked = jdbc.sql(
                            "SELECT id FROM content_drafts WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE")
                    .param("id", draftId)
                    .param("workspaceId", workspaceId)
                    .query(UUID.class)
                    .optional();
            if (locked.isEmpty()) {
                return Optional.empty();
            }
        }
        List<String> brandText = brandText(workspaceId);
        return jdbc.sql("SELECT " + DRAFT_COLUMNS + DRAFT_FROM + " WHERE d.id = :id AND d.workspace_id = :workspaceId")
                .param("id", draftId)
                .param("workspaceId", workspaceId)
                .query((rs, n) -> draftView(rs, brandText))
                .optional();
    }

    private DraftView lockDraft(WorkspaceContext workspace, UUID draftId, int version) {
        DraftView draft = findDraft(workspace.workspaceId(), draftId, true).orElseThrow(NotFoundException::new);
        if (draft.version() != version) {
            throw new ConflictException("This draft was changed by someone else. Reload it and try again.");
        }
        return draft;
    }

    private static BatchView batchView(ResultSet rs, int rowNum) throws SQLException {
        return new BatchView(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("session_title"),
                ContentBatchStatus.valueOf(rs.getString("status")),
                rs.getString("session_type"),
                rs.getString("notes"),
                rs.getInt("caption_count"),
                rs.getBoolean("include_hashtags"),
                rs.getBoolean("include_call_to_action"),
                rs.getInt("run"),
                rs.getString("failure_reason"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("carousel_rationale"),
                strings(rs.getArray("story_ideas")),
                strings(rs.getArray("warnings")),
                rs.getInt("photo_count"),
                rs.getInt("draft_count"),
                rs.getInt("approved_count"),
                instant(rs, "created_at"),
                instant(rs, "completed_at"));
    }

    private static DraftView draftView(ResultSet rs, List<String> brandText) throws SQLException {
        DraftText text = new DraftText(
                rs.getString("caption"), rs.getString("call_to_action"), strings(rs.getArray("hashtags")));
        List<String> sources = new ArrayList<>(brandText);
        sources.add(rs.getString("batch_notes"));
        return new DraftView(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getInt("position"),
                DraftStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                text.caption(),
                text.callToAction(),
                text.hashtags(),
                DraftSource.valueOf(rs.getString("source")),
                rs.getObject("approved_version", Integer.class),
                instant(rs, "approved_at"),
                instant(rs, "updated_at"),
                ContentChecks.hints(text, sources));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record BatchPhoto(UUID assetId, Integer carouselPosition) {}

    /** A batch the job still has to generate, with its photos in selection order. */
    record PendingBatch(
            UUID id,
            UUID workspaceId,
            UUID sessionId,
            String sessionType,
            String notes,
            int captionCount,
            boolean includeHashtags,
            boolean includeCallToAction,
            List<UUID> assetIds) {}

    /**
     * A batch as lists and its page show it. {@code failureReason} is set when it failed, or while it waits for an
     * automatic retry after a problem; {@code provider}, {@code model}, and {@code promptVersion} once it succeeded.
     */
    record BatchView(
            UUID id,
            UUID sessionId,
            String sessionTitle,
            ContentBatchStatus status,
            String sessionType,
            String notes,
            int captionCount,
            boolean includeHashtags,
            boolean includeCallToAction,
            int run,
            String failureReason,
            String provider,
            String model,
            String promptVersion,
            String carouselRationale,
            List<String> storyIdeas,
            List<String> warnings,
            int photoCount,
            int draftCount,
            int approvedCount,
            Instant createdAt,
            Instant completedAt) {}

    /**
     * A batch with its ready photos in selection order (their URLs expire after a few minutes), the suggested
     * carousel order as photo IDs, and its drafts in order.
     */
    record BatchDetail(BatchView batch, List<AssetView> photos, List<UUID> carousel, List<DraftView> drafts) {}

    /**
     * A draft's current version. {@code checks} are review hints for things the photographer did not write
     * themselves, such as an account handle or a price (see {@link ContentChecks}).
     */
    record DraftView(
            UUID id,
            UUID batchId,
            int position,
            DraftStatus status,
            int version,
            String caption,
            String callToAction,
            List<String> hashtags,
            DraftSource source,
            Integer approvedVersion,
            Instant approvedAt,
            Instant updatedAt,
            List<String> checks) {}

    record DraftVersionView(
            int version, String caption, String callToAction, List<String> hashtags, DraftSource source,
            Instant createdAt) {}

    /** A draft with every version, newest first. */
    record DraftDetail(DraftView draft, List<DraftVersionView> versions) {}
}
