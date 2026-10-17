package com.photospace.tasks;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.clients.ClientService;
import com.photospace.clients.ClientService.ClientSummary;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService;
import com.photospace.workspaces.WorkspaceService.MemberView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A workspace's tasks. Every query is scoped by the verified workspace, and linked clients, sessions, and
 * assignees must belong to it. Due dates are calendar dates in the workspace time zone; "overdue" means open and
 * due before today there. Creating, completing, reopening, and deleting a task are audited.
 */
@Service
public class TaskService {

    private static final String COLUMNS = """
            id, client_id, session_id, title, description, due_date, status, assignee_id, source,
            automation_run_id, completed_at, version, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final WorkspaceService workspaces;
    private final ClientService clients;
    private final PhotoSessionService sessions;
    private final AuditService audit;

    TaskService(JdbcClient jdbc, WorkspaceService workspaces, ClientService clients, PhotoSessionService sessions,
            AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.clients = clients;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Transactional
    TaskView create(WorkspaceContext workspace, TaskInput input) {
        requireValidLinks(workspace, input, null);
        UUID id = jdbc.sql("""
                        INSERT INTO tasks (workspace_id, client_id, session_id, title, description, due_date, status,
                                           assignee_id, source, created_by)
                        VALUES (:workspaceId, :clientId, :sessionId, :title, :description, :dueDate, 'OPEN',
                                :assigneeId, 'MANUAL', :userId)
                        RETURNING id
                        """)
                .params(fields(input))
                .param("workspaceId", workspace.workspaceId())
                .param("userId", workspace.userId())
                .query(UUID.class)
                .single();
        audit.record(workspace, AuditAction.TASK_CREATED, AuditTarget.TASK, id, dueDetail(input.dueDate()));
        return get(workspace, id);
    }

    @Transactional(readOnly = true)
    TaskView get(WorkspaceContext workspace, UUID taskId) {
        TaskRow row = find(workspace.workspaceId(), taskId, false).orElseThrow(NotFoundException::new);
        return views(workspace, List.of(row)).getFirst();
    }

    /**
     * Tasks matching every given filter. Open tasks first, then by due date (undated last), then oldest first.
     * {@code overdue} keeps only open tasks due before today in the workspace time zone.
     */
    @Transactional(readOnly = true)
    PageView<TaskView> list(WorkspaceContext workspace, TaskFilter filter, PageQuery page) {
        LocalDate today = LocalDate.now(workspaces.zone(workspace));
        String where = " WHERE workspace_id = :workspaceId"
                + (filter.status() == null ? "" : " AND status = :status")
                + (filter.clientId() == null ? "" : " AND client_id = :clientId")
                + (filter.sessionId() == null ? "" : " AND session_id = :sessionId")
                + (filter.assigneeId() == null ? "" : " AND assignee_id = :assigneeId")
                + (filter.overdue() ? " AND status = 'OPEN' AND due_date < :today" : "");
        Map<String, Object> params = new HashMap<>();
        params.put("workspaceId", workspace.workspaceId());
        params.put("status", filter.status() == null ? null : filter.status().name());
        params.put("clientId", filter.clientId());
        params.put("sessionId", filter.sessionId());
        params.put("assigneeId", filter.assigneeId());
        params.put("today", today);

        long total = jdbc.sql("SELECT count(*) FROM tasks" + where).params(params).query(Long.class).single();
        List<TaskRow> rows = jdbc.sql("SELECT " + COLUMNS + " FROM tasks" + where + """
                         ORDER BY status = 'DONE', due_date NULLS LAST, created_at, id
                         LIMIT :limit OFFSET :offset
                        """)
                .params(params)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(TaskService::taskRow)
                .list();
        return PageView.of(views(workspace, rows), page, total);
    }

    /** Saves new field values if {@code version} is still current. */
    @Transactional
    TaskView update(WorkspaceContext workspace, UUID taskId, int version, TaskInput input) {
        TaskRow current = find(workspace.workspaceId(), taskId, true).orElseThrow(NotFoundException::new);
        if (current.version() != version) {
            throw new ConflictException("This task was changed by someone else. Reload it and try again.");
        }
        requireValidLinks(workspace, input, current);
        jdbc.sql("""
                        UPDATE tasks
                           SET client_id = :clientId, session_id = :sessionId, title = :title,
                               description = :description, due_date = :dueDate, assignee_id = :assigneeId,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .params(fields(input))
                .param("id", taskId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.TASK_UPDATED, AuditTarget.TASK, taskId, dueDetail(input.dueDate()));
        return get(workspace, taskId);
    }

    /** Completes or reopens the task. Asking for the current status changes nothing, so a retry is harmless. */
    @Transactional
    TaskView changeStatus(WorkspaceContext workspace, UUID taskId, TaskStatus target) {
        TaskRow current = find(workspace.workspaceId(), taskId, true).orElseThrow(NotFoundException::new);
        if (current.status() == target) {
            return get(workspace, taskId);
        }
        boolean done = target == TaskStatus.DONE;
        jdbc.sql("""
                        UPDATE tasks
                           SET status = :status,
                               completed_at = CASE WHEN :done THEN now() END,
                               completed_by = CASE WHEN :done THEN CAST(:userId AS uuid) END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", target.name())
                .param("done", done)
                .param("userId", workspace.userId())
                .param("id", taskId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, done ? AuditAction.TASK_COMPLETED : AuditAction.TASK_REOPENED, AuditTarget.TASK,
                taskId, null);
        return get(workspace, taskId);
    }

    @Transactional
    void delete(WorkspaceContext workspace, UUID taskId) {
        int deleted = jdbc.sql("DELETE FROM tasks WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", taskId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        if (deleted == 0) {
            throw new NotFoundException();
        }
        audit.record(workspace, AuditAction.TASK_DELETED, AuditTarget.TASK, taskId, null);
    }

    /**
     * Creates the task of an automation run, unless that run already has one (its job ran twice). Runs without a
     * signed-in member and must be called inside the run's transaction. Returns the run's task ID.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForAutomation(UUID workspaceId, UUID runId, UUID sessionId, UUID clientId, String title,
            LocalDate dueDate) {
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO tasks (workspace_id, client_id, session_id, title, due_date, status, source,
                                           automation_run_id)
                        VALUES (:workspaceId, :clientId, :sessionId, :title, :dueDate, 'OPEN', 'AUTOMATION', :runId)
                        ON CONFLICT (automation_run_id) DO NOTHING
                        RETURNING id
                        """)
                .param("workspaceId", workspaceId)
                .param("clientId", clientId)
                .param("sessionId", sessionId)
                .param("title", title)
                .param("dueDate", dueDate)
                .param("runId", runId)
                .query(UUID.class)
                .optional();
        inserted.ifPresent(id -> audit.record(workspaceId, null, AuditAction.TASK_CREATED, AuditTarget.TASK, id,
                dueDetail(dueDate)));
        return inserted.orElseGet(() -> jdbc.sql(
                        "SELECT id FROM tasks WHERE automation_run_id = :runId AND workspace_id = :workspaceId")
                .param("runId", runId)
                .param("workspaceId", workspaceId)
                .query(UUID.class)
                .single());
    }

    /**
     * Creates a task for another module's workflow, such as an inquiry's review task, inside the caller's
     * transaction. A null {@code actorId} means the system did it. The caller has checked that the client and
     * session belong to {@code workspaceId}; the database's composite foreign keys check it again.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForWorkflow(UUID workspaceId, UUID actorId, TaskSource source, String title, LocalDate dueDate,
            UUID clientId, UUID sessionId) {
        UUID id = jdbc.sql("""
                        INSERT INTO tasks (workspace_id, client_id, session_id, title, due_date, status, source, created_by)
                        VALUES (:workspaceId, :clientId, :sessionId, :title, :dueDate, 'OPEN', :source, :actorId)
                        RETURNING id
                        """)
                .param("workspaceId", workspaceId)
                .param("clientId", clientId)
                .param("sessionId", sessionId)
                .param("title", title)
                .param("dueDate", dueDate)
                .param("source", source.name())
                .param("actorId", actorId)
                .query(UUID.class)
                .single();
        audit.record(workspaceId, actorId, AuditAction.TASK_CREATED, AuditTarget.TASK, id, dueDetail(dueDate));
        return id;
    }

    /**
     * Completes or reopens a workflow's task inside the caller's transaction. Does nothing if the task was deleted
     * or already has that status. Returns whether the task still exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean changeStatusForWorkflow(UUID workspaceId, UUID actorId, UUID taskId, TaskStatus target) {
        Optional<TaskRow> current = find(workspaceId, taskId, true);
        if (current.isEmpty()) {
            return false;
        }
        if (current.get().status() == target) {
            return true;
        }
        boolean done = target == TaskStatus.DONE;
        jdbc.sql("""
                        UPDATE tasks
                           SET status = :status,
                               completed_at = CASE WHEN :done THEN now() END,
                               completed_by = CASE WHEN :done THEN CAST(:userId AS uuid) END,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("status", target.name())
                .param("done", done)
                .param("userId", actorId)
                .param("id", taskId)
                .param("workspaceId", workspaceId)
                .update();
        audit.record(workspaceId, actorId, done ? AuditAction.TASK_COMPLETED : AuditAction.TASK_REOPENED,
                AuditTarget.TASK, taskId, null);
        return true;
    }

    /** The task each of the given automation runs created, by run ID. Runs whose task was deleted are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> tasksOfRuns(WorkspaceContext workspace, List<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT automation_run_id, id FROM tasks
                         WHERE workspace_id = :workspaceId AND automation_run_id IN (:ids)
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("ids", runIds)
                .query((rs, i) -> Map.entry(rs.getObject("automation_run_id", UUID.class), rs.getObject("id", UUID.class)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The client and session must belong to this workspace (a foreign ID gets the same answer as an unknown one)
     * and must not be archived, unless the task already links to them. The assignee must be a member.
     */
    private void requireValidLinks(WorkspaceContext workspace, TaskInput input, TaskRow current) {
        Map<String, String> errors = new HashMap<>();
        if (input.clientId() != null) {
            Optional<ClientSummary> client = clients.findSummary(workspace, input.clientId());
            if (client.isEmpty()) {
                errors.put("clientId", "does not match a client in this workspace");
            } else if (client.get().archived() && (current == null || !input.clientId().equals(current.clientId()))) {
                errors.put("clientId", "is archived; restore the client first");
            }
        }
        if (input.sessionId() != null) {
            Optional<SessionSummary> session = sessions.findSummary(workspace, input.sessionId());
            if (session.isEmpty()) {
                errors.put("sessionId", "does not match a session in this workspace");
            } else if (session.get().archived() && (current == null || !input.sessionId().equals(current.sessionId()))) {
                errors.put("sessionId", "is archived; restore the session first");
            }
        }
        if (input.assigneeId() != null
                && workspaces.findRole(workspace.workspaceId(), input.assigneeId()).isEmpty()) {
            errors.put("assigneeId", "is not a member of this workspace");
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private Optional<TaskRow> find(UUID workspaceId, UUID taskId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tasks WHERE id = :id AND workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", taskId)
                .param("workspaceId", workspaceId)
                .query(TaskService::taskRow)
                .optional();
    }

    private List<TaskView> views(WorkspaceContext workspace, List<TaskRow> rows) {
        LocalDate today = LocalDate.now(workspaces.zone(workspace));
        Map<UUID, ClientSummary> linkedClients = clients.summaries(workspace,
                rows.stream().map(TaskRow::clientId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, SessionSummary> linkedSessions = sessions.summaries(workspace,
                rows.stream().map(TaskRow::sessionId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, Assignee> members = rows.stream().anyMatch(row -> row.assigneeId() != null)
                ? workspaces.listMembers(workspace).stream()
                        .collect(Collectors.toMap(MemberView::userId, m -> new Assignee(m.userId(), m.displayName())))
                : Map.of();
        return rows.stream()
                .map(row -> new TaskView(
                        row.id(),
                        row.title(),
                        row.description(),
                        row.dueDate(),
                        row.status() == TaskStatus.OPEN && row.dueDate() != null && row.dueDate().isBefore(today),
                        row.status(),
                        row.clientId() == null ? null : linkedClients.get(row.clientId()),
                        row.sessionId() == null ? null : linkedSessions.get(row.sessionId()),
                        row.assigneeId() == null ? null : members.get(row.assigneeId()),
                        row.source(),
                        row.completedAt(),
                        row.version(),
                        row.createdAt(),
                        row.updatedAt()))
                .toList();
    }

    private static Map<String, Object> fields(TaskInput input) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("clientId", input.clientId());
        fields.put("sessionId", input.sessionId());
        fields.put("title", input.title());
        fields.put("description", input.description());
        fields.put("dueDate", input.dueDate());
        fields.put("assigneeId", input.assigneeId());
        return fields;
    }

    private static String dueDetail(LocalDate dueDate) {
        return dueDate == null ? null : "due " + dueDate;
    }

    private static TaskRow taskRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRow(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("due_date", LocalDate.class),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getObject("assignee_id", UUID.class),
                TaskSource.valueOf(rs.getString("source")),
                instant(rs, "completed_at"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record TaskRow(
            UUID id,
            UUID clientId,
            UUID sessionId,
            String title,
            String description,
            LocalDate dueDate,
            TaskStatus status,
            UUID assigneeId,
            TaskSource source,
            Instant completedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** List filters; null or false means "any". */
    record TaskFilter(TaskStatus status, UUID clientId, UUID sessionId, UUID assigneeId, boolean overdue) {}

    public record Assignee(UUID userId, String displayName) {}

    /**
     * A task as the API returns it. {@code dueDate} is a date in the workspace time zone, and {@code overdue} is
     * computed there. A linked client or session is a summary; a deleted assignee shows as null.
     */
    public record TaskView(
            UUID id,
            String title,
            String description,
            LocalDate dueDate,
            boolean overdue,
            TaskStatus status,
            ClientSummary client,
            SessionSummary session,
            Assignee assignee,
            TaskSource source,
            Instant completedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}
}
