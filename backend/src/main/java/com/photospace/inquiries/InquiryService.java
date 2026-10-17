package com.photospace.inquiries;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.clients.ClientService;
import com.photospace.clients.ClientService.ClientMatch;
import com.photospace.clients.ClientService.ClientSummary;
import com.photospace.inquiries.FormConfig.Question;
import com.photospace.inquiries.InquiryFormService.VersionView;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.tasks.TaskService;
import com.photospace.tasks.TaskSource;
import com.photospace.tasks.TaskStatus;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A workspace's inquiries. An inquiry stays apart from clients until a member acts on it: matching only looks
 * for clients with the same email and never links or changes one. Associating links a client (or creates one from
 * the answers) and creates one {@code INQUIRY} session and one follow-up task, all in one transaction; dismissing
 * creates nothing. Both are audited, take the version the member saw, and are harmless to repeat. Nothing is
 * booked, sent, or charged.
 */
@Service
class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);
    private static final TypeReference<Map<String, String>> ANSWERS = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, form_version_id, status, answers, contact_name, contact_email, email_normalized, contact_phone,
            service, preferred_date, source, referrer, utm_source, utm_medium, utm_campaign, utm_term, utm_content,
            client_id, client_created, session_id, review_task_id, follow_up_task_id, associated_at, dismissed_at,
            version, submitted_at, updated_at""";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final InquiryFormService forms;
    private final WorkspaceService workspaces;
    private final ClientService clients;
    private final PhotoSessionService sessions;
    private final TaskService tasks;
    private final AuditService audit;

    InquiryService(JdbcClient jdbc, JsonMapper json, InquiryFormService forms, WorkspaceService workspaces,
            ClientService clients, PhotoSessionService sessions, TaskService tasks, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.forms = forms;
        this.workspaces = workspaces;
        this.clients = clients;
        this.sessions = sessions;
        this.tasks = tasks;
        this.audit = audit;
    }

    /**
     * Newest first. Without a status, every inquiry except dismissed ones, or with {@code open} only those still
     * waiting to be associated or dismissed.
     */
    @Transactional(readOnly = true)
    PageView<InquirySummary> list(WorkspaceContext workspace, InquiryStatus status, boolean open, PageQuery page) {
        String where = " WHERE workspace_id = :workspaceId"
                + (status != null ? " AND status = :status"
                        : open ? " AND status IN ('NEW', 'NEEDS_REVIEW')" : " AND status <> 'DISMISSED'");
        String statusName = status == null ? null : status.name();
        long total = jdbc.sql("SELECT count(*) FROM inquiries" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("status", statusName)
                .query(Long.class)
                .single();
        List<InquiryRow> rows = jdbc.sql("SELECT " + COLUMNS + " FROM inquiries" + where
                        + " ORDER BY submitted_at DESC, id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("status", statusName)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(this::inquiryRow)
                .list();
        return PageView.of(rows, page, total).map(InquiryRow::summary);
    }

    @Transactional(readOnly = true)
    InquiryView get(WorkspaceContext workspace, UUID inquiryId) {
        return view(workspace, find(workspace.workspaceId(), inquiryId, false).orElseThrow(NotFoundException::new));
    }

    /**
     * Links the inquiry to an active client of this workspace ({@code clientId}) or to a new client made from its
     * answers ({@code createClient}), and creates its session and follow-up task. Repeating a request that already
     * succeeded returns the result without creating anything.
     */
    @Transactional
    InquiryView associate(WorkspaceContext workspace, UUID inquiryId, int version, UUID clientId, boolean createClient) {
        if ((clientId == null) == !createClient) {
            throw InvalidFieldsException.of("clientId", "choose a client or create a new one");
        }
        InquiryRow inquiry = find(workspace.workspaceId(), inquiryId, true).orElseThrow(NotFoundException::new);
        if (inquiry.status() == InquiryStatus.ASSOCIATED) {
            boolean sameRequest = createClient ? inquiry.clientCreated() : clientId.equals(inquiry.clientId());
            if (sameRequest) {
                return view(workspace, inquiry);
            }
            throw new ConflictException("This inquiry is already linked to a client.");
        }
        requireOpen(inquiry, "link it to a client");
        requireVersion(inquiry, version);

        ClientSummary client;
        if (createClient) {
            client = clients.createForWorkflow(workspace, inquiry.name(), inquiry.email(), inquiry.phone());
        } else {
            client = clients.findSummary(workspace, clientId)
                    .filter(found -> !found.archived())
                    .orElseThrow(() -> InvalidFieldsException.of("clientId",
                            "does not match an active client in this workspace"));
        }
        SessionSummary session = sessions.createForWorkflow(workspace, client.id(),
                limit("Inquiry: " + inquiry.name()), inquiry.service(), sessionNotes(inquiry));
        LocalDate today = LocalDate.now(workspaces.zone(workspace));
        UUID followUp = tasks.createForWorkflow(workspace.workspaceId(), workspace.userId(), TaskSource.INQUIRY,
                limit("Follow up with " + inquiry.name()), today.plusDays(1), client.id(), session.id());
        if (inquiry.reviewTaskId() != null) {
            tasks.changeStatusForWorkflow(workspace.workspaceId(), workspace.userId(), inquiry.reviewTaskId(),
                    TaskStatus.DONE);
        }
        jdbc.sql("""
                        UPDATE inquiries
                           SET status = 'ASSOCIATED', client_id = :clientId, client_created = :created,
                               session_id = :sessionId, follow_up_task_id = :taskId, associated_by = :userId,
                               associated_at = now(), version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("clientId", client.id())
                .param("created", createClient)
                .param("sessionId", session.id())
                .param("taskId", followUp)
                .param("userId", workspace.userId())
                .param("id", inquiryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.INQUIRY_ASSOCIATED, AuditTarget.INQUIRY, inquiryId,
                createClient ? "new client" : "existing client");
        return get(workspace, inquiryId);
    }

    /** Sets the inquiry aside and completes its review task. Creates nothing; the answers are kept. */
    @Transactional
    InquiryView dismiss(WorkspaceContext workspace, UUID inquiryId, int version) {
        InquiryRow inquiry = find(workspace.workspaceId(), inquiryId, true).orElseThrow(NotFoundException::new);
        if (inquiry.status() == InquiryStatus.DISMISSED) {
            return view(workspace, inquiry);
        }
        requireOpen(inquiry, "dismiss it");
        requireVersion(inquiry, version);
        jdbc.sql("""
                        UPDATE inquiries
                           SET status = 'DISMISSED', dismissed_by = :userId, dismissed_at = now(), version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("userId", workspace.userId())
                .param("id", inquiryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        if (inquiry.reviewTaskId() != null) {
            tasks.changeStatusForWorkflow(workspace.workspaceId(), workspace.userId(), inquiry.reviewTaskId(),
                    TaskStatus.DONE);
        }
        audit.record(workspace, AuditAction.INQUIRY_DISMISSED, AuditTarget.INQUIRY, inquiryId, null);
        return get(workspace, inquiryId);
    }

    /** Brings a dismissed inquiry back, matched again against the clients as they are now. */
    @Transactional
    InquiryView reopen(WorkspaceContext workspace, UUID inquiryId, int version) {
        InquiryRow inquiry = find(workspace.workspaceId(), inquiryId, true).orElseThrow(NotFoundException::new);
        if (InquiryStatus.OPEN.contains(inquiry.status())) {
            return view(workspace, inquiry);
        }
        if (inquiry.status() != InquiryStatus.DISMISSED) {
            throw new ConflictException("Only a dismissed inquiry can be reopened.");
        }
        requireVersion(inquiry, version);
        InquiryStatus status = clients.findActiveByEmail(workspace.workspaceId(), inquiry.emailNormalized()).isEmpty()
                ? InquiryStatus.NEW
                : InquiryStatus.NEEDS_REVIEW;
        boolean reviewTaskKept = inquiry.reviewTaskId() != null && tasks.changeStatusForWorkflow(
                workspace.workspaceId(), workspace.userId(), inquiry.reviewTaskId(), TaskStatus.OPEN);
        UUID reviewTask = reviewTaskKept
                ? inquiry.reviewTaskId()
                : createReviewTask(workspace.workspaceId(), workspace.userId(), inquiry);
        jdbc.sql("""
                        UPDATE inquiries
                           SET status = :status, review_task_id = :taskId, dismissed_by = NULL, dismissed_at = NULL,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", status.name())
                .param("taskId", reviewTask)
                .param("id", inquiryId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.INQUIRY_REOPENED, AuditTarget.INQUIRY, inquiryId, status.name());
        return get(workspace, inquiryId);
    }

    // --- Used by MatchInquiryJob, which runs without a signed-in member. ---

    /**
     * Looks for active clients with the inquiry's email and creates its review task, if the inquiry is still
     * {@code RECEIVED}. {@code gaveUp} sends it to review whatever the match, when matching kept failing.
     */
    @Transactional
    void match(UUID workspaceId, UUID inquiryId, boolean gaveUp) {
        Optional<InquiryRow> found = find(workspaceId, inquiryId, true)
                .filter(inquiry -> inquiry.status() == InquiryStatus.RECEIVED);
        if (found.isEmpty()) {
            return; // Matched by an earlier delivery.
        }
        InquiryRow inquiry = found.get();
        int matches = gaveUp ? 0 : clients.findActiveByEmail(workspaceId, inquiry.emailNormalized()).size();
        InquiryStatus status = gaveUp || matches > 0 ? InquiryStatus.NEEDS_REVIEW : InquiryStatus.NEW;
        UUID reviewTask = createReviewTask(workspaceId, null, inquiry);
        jdbc.sql("""
                        UPDATE inquiries SET status = :status, review_task_id = :taskId, version = version + 1,
                                             updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", status.name())
                .param("taskId", reviewTask)
                .param("id", inquiryId)
                .param("workspaceId", workspaceId)
                .update();
        audit.record(workspaceId, null, AuditAction.INQUIRY_MATCHED, AuditTarget.INQUIRY, inquiryId,
                gaveUp ? status + ", matching failed" : status + ", " + matches + " matching client(s)");
        log.info("Inquiry {} is {}", inquiryId, status);
    }

    private UUID createReviewTask(UUID workspaceId, UUID actorId, InquiryRow inquiry) {
        LocalDate today = LocalDate.now(workspaces.zone(workspaceId));
        return tasks.createForWorkflow(workspaceId, actorId, TaskSource.INQUIRY,
                limit("Review inquiry from " + inquiry.name()), today, null, null);
    }

    private static void requireOpen(InquiryRow inquiry, String action) {
        switch (inquiry.status()) {
            case NEW, NEEDS_REVIEW -> {}
            case RECEIVED -> throw new ConflictException(
                    "This inquiry is still being checked for matching clients. Try again in a moment.");
            case DISMISSED -> throw new ConflictException("This inquiry is dismissed. Reopen it before you " + action + ".");
            case ASSOCIATED -> throw new ConflictException("This inquiry is already linked to a client.");
        }
    }

    private static void requireVersion(InquiryRow inquiry, int version) {
        if (inquiry.version() != version) {
            throw new ConflictException("This inquiry was changed by someone else. Reload it and try again.");
        }
    }

    /** The preferred date goes into the session's notes: it is a wish, not a booking. */
    private static String sessionNotes(InquiryRow inquiry) {
        return inquiry.preferredDate() == null
                ? "Created from an inquiry."
                : "Created from an inquiry. Preferred date: " + inquiry.preferredDate() + " (not booked).";
    }

    private static String limit(String title) {
        return title.length() <= 200 ? title : title.substring(0, 200);
    }

    private Optional<InquiryRow> find(UUID workspaceId, UUID inquiryId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM inquiries WHERE id = :id AND workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", inquiryId)
                .param("workspaceId", workspaceId)
                .query(this::inquiryRow)
                .optional();
    }

    private InquiryView view(WorkspaceContext workspace, InquiryRow row) {
        VersionView version = forms.version(workspace.workspaceId(), row.formVersionId());
        List<AnswerView> answers = version.config().questions().stream()
                .filter(question -> row.answers().containsKey(question.id()))
                .map(question -> answer(question, row.answers().get(question.id())))
                .toList();
        List<ClientMatch> matches = InquiryStatus.OPEN.contains(row.status())
                ? clients.findActiveByEmail(workspace.workspaceId(), row.emailNormalized())
                : List.of();
        ClientSummary client = row.clientId() == null ? null
                : clients.findSummary(workspace, row.clientId()).orElse(null);
        SessionSummary session = row.sessionId() == null ? null
                : sessions.findSummary(workspace, row.sessionId()).orElse(null);
        return new InquiryView(
                row.id(),
                row.status(),
                row.name(),
                row.email(),
                row.phone(),
                row.service(),
                row.preferredDate(),
                answers,
                version.number(),
                row.source(),
                row.referrer(),
                new Utm(row.utmSource(), row.utmMedium(), row.utmCampaign(), row.utmTerm(), row.utmContent()),
                matches,
                client,
                row.clientCreated(),
                session,
                row.reviewTaskId(),
                row.followUpTaskId(),
                row.associatedAt(),
                row.dismissedAt(),
                row.version(),
                row.submittedAt(),
                row.updatedAt());
    }

    private static AnswerView answer(Question question, String value) {
        return new AnswerView(question.id(), question.label(), question.type(), value);
    }

    private InquiryRow inquiryRow(ResultSet rs, int rowNum) throws SQLException {
        return new InquiryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("form_version_id", UUID.class),
                InquiryStatus.valueOf(rs.getString("status")),
                json.readValue(rs.getString("answers"), ANSWERS),
                rs.getString("contact_name"),
                rs.getString("contact_email"),
                rs.getString("email_normalized"),
                rs.getString("contact_phone"),
                rs.getString("service"),
                rs.getObject("preferred_date", LocalDate.class),
                InquirySource.valueOf(rs.getString("source")),
                rs.getString("referrer"),
                rs.getString("utm_source"),
                rs.getString("utm_medium"),
                rs.getString("utm_campaign"),
                rs.getString("utm_term"),
                rs.getString("utm_content"),
                rs.getObject("client_id", UUID.class),
                rs.getBoolean("client_created"),
                rs.getObject("session_id", UUID.class),
                rs.getObject("review_task_id", UUID.class),
                rs.getObject("follow_up_task_id", UUID.class),
                instant(rs, "associated_at"),
                instant(rs, "dismissed_at"),
                rs.getInt("version"),
                instant(rs, "submitted_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record InquiryRow(
            UUID id,
            UUID formVersionId,
            InquiryStatus status,
            Map<String, String> answers,
            String name,
            String email,
            String emailNormalized,
            String phone,
            String service,
            LocalDate preferredDate,
            InquirySource source,
            String referrer,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmTerm,
            String utmContent,
            UUID clientId,
            boolean clientCreated,
            UUID sessionId,
            UUID reviewTaskId,
            UUID followUpTaskId,
            Instant associatedAt,
            Instant dismissedAt,
            int version,
            Instant submittedAt,
            Instant updatedAt) {

        InquirySummary summary() {
            return new InquirySummary(id, status, name, email, service, preferredDate, source, submittedAt);
        }
    }

    record InquirySummary(UUID id, InquiryStatus status, String name, String email, String service,
            LocalDate preferredDate, InquirySource source, Instant submittedAt) {}

    /** One answered question, with its label and type from the form version the visitor saw. */
    record AnswerView(String questionId, String label, QuestionType type, String value) {}

    record Utm(String source, String medium, String campaign, String term, String content) {}

    /**
     * An inquiry as members see it. {@code matches} lists active clients with the same email while the inquiry
     * is open. The client, session, and task links are set once it is associated (the review task when matched).
     */
    record InquiryView(
            UUID id,
            InquiryStatus status,
            String name,
            String email,
            String phone,
            String service,
            LocalDate preferredDate,
            List<AnswerView> answers,
            int formVersion,
            InquirySource source,
            String referrer,
            Utm utm,
            List<ClientMatch> matches,
            ClientSummary client,
            boolean clientCreated,
            SessionSummary session,
            UUID reviewTaskId,
            UUID followUpTaskId,
            Instant associatedAt,
            Instant dismissedAt,
            int version,
            Instant submittedAt,
            Instant updatedAt) {}
}
