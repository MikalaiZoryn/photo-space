package com.photospace.inquiries;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workspace's General Inquiry form. Members edit a draft; publishing copies the draft into a new immutable
 * version, which is what visitors see until the next publish. Unpublishing stops new submissions and keeps
 * everything already submitted. Publishing and unpublishing are audited.
 *
 * <p>The public slug is 128 random bits. It identifies the form in public URLs; it is not a secret, since the
 * form is meant to be shared, but it can't be guessed from anything else.
 */
@Service
class InquiryFormService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9_-]{22}");

    private static final String COLUMNS = """
            f.id, f.slug, f.status, f.draft_config, f.version, f.created_at, f.updated_at,
            v.id AS published_id, v.number AS published_number, v.config AS published_config,
            v.published_at AS published_at""";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditService audit;

    InquiryFormService(JdbcClient jdbc, JsonMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    FormView get(WorkspaceContext workspace) {
        return find(workspace.workspaceId(), false).map(FormRow::view).orElseThrow(NotFoundException::new);
    }

    /** Creates the form from the default template. If it already exists, returns it unchanged. */
    @Transactional
    Created create(WorkspaceContext workspace) {
        int inserted = jdbc.sql("""
                        INSERT INTO public_forms (workspace_id, slug, status, draft_config)
                        VALUES (:workspaceId, :slug, 'DRAFT', CAST(:config AS jsonb))
                        ON CONFLICT (workspace_id) DO NOTHING
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("slug", newSlug())
                .param("config", write(FormConfig.defaultTemplate()))
                .update();
        return new Created(get(workspace), inserted == 1);
    }

    /** Saves the draft if {@code version} is still current. Visitors keep seeing the published version. */
    @Transactional
    FormView updateDraft(WorkspaceContext workspace, int version, FormConfig draft) {
        FormConfig valid = draft.validated();
        FormRow current = lockCurrent(workspace, version);
        jdbc.sql("""
                        UPDATE public_forms SET draft_config = CAST(:config AS jsonb), version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("config", write(valid))
                .param("id", current.id())
                .param("workspaceId", workspace.workspaceId())
                .update();
        return get(workspace);
    }

    /**
     * Publishes the saved draft if {@code version} is still current. A draft that differs from the last published
     * version becomes a new version; publishing an unchanged draft again changes nothing.
     */
    @Transactional
    FormView publish(WorkspaceContext workspace, int version) {
        FormRow current = find(workspace.workspaceId(), true).orElseThrow(NotFoundException::new);
        boolean unchanged = current.published() != null && current.published().config().equals(current.draft());
        if (unchanged && current.status() == FormStatus.PUBLISHED) {
            return current.view();
        }
        requireVersion(current, version);
        current.draft().validated();
        UUID versionId;
        int number;
        if (unchanged) {
            versionId = current.published().id();
            number = current.published().number();
        } else {
            number = current.published() == null ? 1 : current.published().number() + 1;
            versionId = jdbc.sql("""
                            INSERT INTO public_form_versions (workspace_id, form_id, number, config, published_by)
                            VALUES (:workspaceId, :formId, :number, CAST(:config AS jsonb), :userId)
                            RETURNING id
                            """)
                    .param("workspaceId", workspace.workspaceId())
                    .param("formId", current.id())
                    .param("number", number)
                    .param("config", write(current.draft()))
                    .param("userId", workspace.userId())
                    .query(UUID.class)
                    .single();
        }
        jdbc.sql("""
                        UPDATE public_forms
                           SET status = 'PUBLISHED', published_version_id = :versionId, version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("versionId", versionId)
                .param("id", current.id())
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.INQUIRY_FORM_PUBLISHED, AuditTarget.INQUIRY_FORM, current.id(),
                "version " + number);
        return get(workspace);
    }

    /** Stops new submissions. Unpublishing a form that is not published changes nothing. */
    @Transactional
    FormView unpublish(WorkspaceContext workspace) {
        FormRow current = find(workspace.workspaceId(), true).orElseThrow(NotFoundException::new);
        if (current.status() != FormStatus.PUBLISHED) {
            return current.view();
        }
        jdbc.sql("""
                        UPDATE public_forms SET status = 'UNPUBLISHED', version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("id", current.id())
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.INQUIRY_FORM_UNPUBLISHED, AuditTarget.INQUIRY_FORM, current.id(),
                "version " + current.published().number());
        return get(workspace);
    }

    // --- Used by the public pages, which know only the slug. ---

    /**
     * The published version behind {@code slug}. Empty for malformed and unknown slugs and for forms that are not
     * published, alike.
     */
    @Transactional(readOnly = true)
    Optional<Published> findPublished(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT f.workspace_id, f.id AS form_id, v.id AS version_id, v.config
                          FROM public_forms f
                          JOIN public_form_versions v
                            ON v.id = f.published_version_id AND v.form_id = f.id AND v.workspace_id = f.workspace_id
                         WHERE f.slug = :slug AND f.status = 'PUBLISHED'
                        """)
                .param("slug", slug)
                .query((rs, i) -> new Published(
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("form_id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        read(rs.getString("config"))))
                .optional();
    }

    /** A published version of this workspace's form, as an inquiry submitted against it shows its questions. */
    @Transactional(readOnly = true)
    VersionView version(UUID workspaceId, UUID versionId) {
        return jdbc.sql("""
                        SELECT id, number, config, published_at FROM public_form_versions
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("id", versionId)
                .param("workspaceId", workspaceId)
                .query(this::versionView)
                .single();
    }

    private FormRow lockCurrent(WorkspaceContext workspace, int version) {
        FormRow current = find(workspace.workspaceId(), true).orElseThrow(NotFoundException::new);
        requireVersion(current, version);
        return current;
    }

    private static void requireVersion(FormRow current, int version) {
        if (current.version() != version) {
            throw new ConflictException("The form was changed by someone else. Reload it and try again.");
        }
    }

    private Optional<FormRow> find(UUID workspaceId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM public_forms f
                         LEFT JOIN public_form_versions v
                           ON v.id = f.published_version_id AND v.form_id = f.id AND v.workspace_id = f.workspace_id
                        WHERE f.workspace_id = :workspaceId""" + (forUpdate ? " FOR UPDATE OF f" : ""))
                .param("workspaceId", workspaceId)
                .query(this::formRow)
                .optional();
    }

    private FormRow formRow(ResultSet rs, int rowNum) throws SQLException {
        UUID publishedId = rs.getObject("published_id", UUID.class);
        return new FormRow(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                FormStatus.valueOf(rs.getString("status")),
                read(rs.getString("draft_config")),
                publishedId == null ? null : new VersionView(
                        publishedId,
                        rs.getInt("published_number"),
                        read(rs.getString("published_config")),
                        instant(rs, "published_at")),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private VersionView versionView(ResultSet rs, int rowNum) throws SQLException {
        return new VersionView(
                rs.getObject("id", UUID.class),
                rs.getInt("number"),
                read(rs.getString("config")),
                instant(rs, "published_at"));
    }

    private String write(FormConfig config) {
        return json.writeValueAsString(config);
    }

    private FormConfig read(String config) {
        return json.readValue(config, FormConfig.class);
    }

    private static String newSlug() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record FormRow(UUID id, String slug, FormStatus status, FormConfig draft, VersionView published,
            int version, Instant createdAt, Instant updatedAt) {

        FormView view() {
            return new FormView(id, status, slug, draft, published,
                    published == null || !published.config().equals(draft), version, createdAt, updatedAt);
        }
    }

    record Created(FormView form, boolean created) {}

    /** The live version behind a slug, with the workspace it belongs to. */
    record Published(UUID workspaceId, UUID formId, UUID versionId, FormConfig config) {}

    record VersionView(UUID id, int number, FormConfig config, Instant publishedAt) {}

    /**
     * The form as members see it. {@code published} is the version visitors see while {@code status} is
     * {@code PUBLISHED}, and the last one published otherwise. {@code unpublishedChanges} says whether the draft
     * differs from it.
     */
    record FormView(UUID id, FormStatus status, String slug, FormConfig draft, VersionView published,
            boolean unpublishedChanges, int version, Instant createdAt, Instant updatedAt) {}
}
