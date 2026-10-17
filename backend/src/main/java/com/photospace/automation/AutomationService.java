package com.photospace.automation;

import com.photospace.audit.AuditAction;
import com.photospace.audit.AuditService;
import com.photospace.audit.AuditTarget;
import com.photospace.jobs.JobService;
import com.photospace.jobs.JobView;
import com.photospace.sessions.PhotoSessionService;
import com.photospace.sessions.PhotoSessionService.SessionSummary;
import com.photospace.sessions.SessionStatus;
import com.photospace.sessions.SessionStatusChanged;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.ConflictException;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.shared.web.NotFoundException;
import com.photospace.tasks.TaskService;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * Deterministic workflow rules. The only rule kind is "when a session changes to status X, create a task due N
 * days later". A status change inserts one run per matching enabled rule, in the transaction of the change, and
 * queues a job for it (the job table is the outbox). A rule fires at most once per session: the run is unique per
 * rule and session, and the task is unique per run, so a repeated event or job never creates a second task. No
 * AI is involved; the task's title and due date come from the rule as it was when it fired.
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);

    /** Statuses a rule can react to: every status a session changes to after it is created. */
    static final Set<SessionStatus> TRIGGERS =
            Set.of(SessionStatus.BOOKED, SessionStatus.COMPLETED, SessionStatus.DELIVERED, SessionStatus.CANCELLED);

    private static final String RULE_COLUMNS =
            "id, trigger_status, task_title, due_in_days, enabled, version, created_at, updated_at";
    private static final String RUN_COLUMNS = """
            id, rule_id, session_id, client_id, trigger_status, task_title, due_date, status, run_count,
            failure_reason, triggered_at, finished_at""";

    private final JdbcClient jdbc;
    private final WorkspaceService workspaces;
    private final PhotoSessionService sessions;
    private final TaskService tasks;
    private final JobService jobs;
    private final AuditService audit;

    AutomationService(JdbcClient jdbc, WorkspaceService workspaces, PhotoSessionService sessions, TaskService tasks,
            JobService jobs, AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.tasks = tasks;
        this.jobs = jobs;
        this.audit = audit;
    }

    // --- Rules: members read them, owners change them. ---

    @Transactional(readOnly = true)
    List<RuleView> listRules(WorkspaceContext workspace) {
        return jdbc.sql("SELECT " + RULE_COLUMNS + " FROM automation_rules WHERE workspace_id = :workspaceId"
                        + " ORDER BY created_at, id")
                .param("workspaceId", workspace.workspaceId())
                .query(AutomationService::ruleView)
                .list();
    }

    @Transactional
    RuleView createRule(WorkspaceContext workspace, RuleInput input) {
        workspace.requireOwner();
        requireTrigger(input);
        UUID id = jdbc.sql("""
                        INSERT INTO automation_rules (workspace_id, trigger_status, task_title, due_in_days, enabled, created_by)
                        VALUES (:workspaceId, :triggerStatus, :taskTitle, :dueInDays, :enabled, :userId)
                        RETURNING id
                        """)
                .param("workspaceId", workspace.workspaceId())
                .param("triggerStatus", input.triggerStatus().name())
                .param("taskTitle", input.taskTitle())
                .param("dueInDays", input.dueInDays())
                .param("enabled", input.enabled())
                .param("userId", workspace.userId())
                .query(UUID.class)
                .single();
        audit.record(workspace, AuditAction.AUTOMATION_RULE_CREATED, AuditTarget.AUTOMATION_RULE, id, detail(input));
        return findRule(workspace.workspaceId(), id).orElseThrow();
    }

    /** Saves new settings if {@code version} is still current. Runs that already fired keep their task. */
    @Transactional
    RuleView updateRule(WorkspaceContext workspace, UUID ruleId, int version, RuleInput input) {
        workspace.requireOwner();
        RuleView current = findRule(workspace.workspaceId(), ruleId).orElseThrow(NotFoundException::new);
        if (current.version() != version) {
            throw new ConflictException("This rule was changed by someone else. Reload it and try again.");
        }
        requireTrigger(input);
        jdbc.sql("""
                        UPDATE automation_rules
                           SET trigger_status = :triggerStatus, task_title = :taskTitle, due_in_days = :dueInDays,
                               enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("triggerStatus", input.triggerStatus().name())
                .param("taskTitle", input.taskTitle())
                .param("dueInDays", input.dueInDays())
                .param("enabled", input.enabled())
                .param("id", ruleId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        audit.record(workspace, AuditAction.AUTOMATION_RULE_UPDATED, AuditTarget.AUTOMATION_RULE, ruleId,
                detail(input));
        return findRule(workspace.workspaceId(), ruleId).orElseThrow();
    }

    // --- Runs. ---

    /**
     * Fires the matching enabled rules for a session status change. Runs in the transaction of the change: the
     * runs and their jobs commit or roll back with it.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSessionStatusChanged(SessionStatusChanged event) {
        if (!TRIGGERS.contains(event.to())) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(event.occurredAt(), workspaces.zone(event.workspaceId()));
        List<UUID> runIds = jdbc.sql("""
                        INSERT INTO automation_runs (workspace_id, rule_id, session_id, client_id, trigger_status,
                                                     task_title, due_date, status, triggered_by, triggered_at)
                        SELECT r.workspace_id, r.id, :sessionId, :clientId, r.trigger_status,
                               r.task_title, CAST(:today AS date) + r.due_in_days, 'PENDING', :actorId, :occurredAt
                          FROM automation_rules r
                         WHERE r.workspace_id = :workspaceId AND r.trigger_status = :status AND r.enabled
                        ON CONFLICT (rule_id, session_id) DO NOTHING
                        RETURNING id
                        """)
                .param("sessionId", event.sessionId())
                .param("clientId", event.clientId())
                .param("today", today)
                .param("actorId", event.actorId())
                .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
                .param("workspaceId", event.workspaceId())
                .param("status", event.to().name())
                .query(UUID.class)
                .list();
        for (UUID runId : runIds) {
            jobs.enqueue(event.workspaceId(), RunAutomationJob.TYPE, runId, jobKey(runId, 1));
        }
        if (!runIds.isEmpty()) {
            log.info("Session {} changed to {}: {} automation run(s) queued", event.sessionId(), event.to(),
                    runIds.size());
        }
    }

    /** The workspace's runs, newest first, optionally of one status. */
    @Transactional(readOnly = true)
    PageView<RunView> listRuns(WorkspaceContext workspace, RunStatus status, PageQuery page) {
        String where = " WHERE workspace_id = :workspaceId" + (status == null ? "" : " AND status = :status");
        String statusName = status == null ? null : status.name();
        long total = jdbc.sql("SELECT count(*) FROM automation_runs" + where)
                .param("workspaceId", workspace.workspaceId())
                .param("status", statusName)
                .query(Long.class)
                .single();
        List<RunRow> rows = jdbc.sql("SELECT " + RUN_COLUMNS + " FROM automation_runs" + where
                        + " ORDER BY triggered_at DESC, id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspace.workspaceId())
                .param("status", statusName)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(AutomationService::runRow)
                .list();
        return PageView.of(views(workspace, rows), page, total);
    }

    /** Starts a failed run again, with a new job. Retrying a run that is not failed is refused. */
    @Transactional
    RunView retryRun(WorkspaceContext workspace, UUID runId) {
        RunRow run = findRun(workspace.workspaceId(), runId, true).orElseThrow(NotFoundException::new);
        if (run.status() != RunStatus.FAILED) {
            throw new ConflictException("Only a failed run can be retried.");
        }
        int runCount = run.runCount() + 1;
        jdbc.sql("""
                        UPDATE automation_runs
                           SET status = 'PENDING', run_count = :runCount, failure_reason = NULL, finished_at = NULL,
                               updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("runCount", runCount)
                .param("id", runId)
                .param("workspaceId", workspace.workspaceId())
                .update();
        jobs.enqueue(workspace.workspaceId(), RunAutomationJob.TYPE, runId, jobKey(runId, runCount));
        audit.record(workspace, AuditAction.AUTOMATION_RUN_RETRIED, AuditTarget.AUTOMATION_RUN, runId,
                "run " + runCount);
        return views(workspace, List.of(findRun(workspace.workspaceId(), runId, false).orElseThrow())).getFirst();
    }

    // --- Used by RunAutomationJob, which runs without a signed-in member. ---

    /** Creates the run's task and ends the run, in one transaction. Does nothing unless the run is pending. */
    @Transactional
    void execute(UUID workspaceId, UUID runId) {
        Optional<RunRow> found = findRun(workspaceId, runId, true).filter(run -> run.status() == RunStatus.PENDING);
        if (found.isEmpty()) {
            return; // Finished by an earlier delivery.
        }
        RunRow run = found.get();
        UUID taskId = tasks.createForAutomation(workspaceId, runId, run.sessionId(), run.clientId(), run.taskTitle(),
                run.dueDate());
        jdbc.sql("""
                        UPDATE automation_runs
                           SET status = 'SUCCEEDED', failure_reason = NULL, finished_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId
                        """)
                .param("id", runId)
                .param("workspaceId", workspaceId)
                .update();
        audit.record(workspaceId, null, AuditAction.AUTOMATION_RUN_SUCCEEDED, AuditTarget.AUTOMATION_RUN, runId,
                "run " + run.runCount());
        log.info("Automation run {} created task {}", runId, taskId);
    }

    /** Marks a pending run failed with a fixed, safe reason. */
    @Transactional
    void fail(UUID workspaceId, UUID runId, String reason) {
        int failed = jdbc.sql("""
                        UPDATE automation_runs
                           SET status = 'FAILED', failure_reason = :reason, finished_at = now(), updated_at = now()
                         WHERE id = :id AND workspace_id = :workspaceId AND status = 'PENDING'
                        """)
                .param("reason", reason)
                .param("id", runId)
                .param("workspaceId", workspaceId)
                .update();
        if (failed == 1) {
            audit.record(workspaceId, null, AuditAction.AUTOMATION_RUN_FAILED, AuditTarget.AUTOMATION_RUN, runId,
                    null);
        }
    }

    private static void requireTrigger(RuleInput input) {
        if (!TRIGGERS.contains(input.triggerStatus())) {
            throw InvalidFieldsException.of("triggerStatus", "must be BOOKED, COMPLETED, DELIVERED, or CANCELLED");
        }
    }

    /** The job's idempotency key: one job per start of a run, so a retry gets a new job. */
    private static String jobKey(UUID runId, int runCount) {
        return "automation-run:" + runId + ":" + runCount;
    }

    private static String detail(RuleInput input) {
        return input.triggerStatus() + ", due in " + input.dueInDays() + " days, "
                + (input.enabled() ? "enabled" : "disabled");
    }

    private Optional<RuleView> findRule(UUID workspaceId, UUID ruleId) {
        return jdbc.sql("SELECT " + RULE_COLUMNS + " FROM automation_rules WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", ruleId)
                .param("workspaceId", workspaceId)
                .query(AutomationService::ruleView)
                .optional();
    }

    private Optional<RunRow> findRun(UUID workspaceId, UUID runId, boolean forUpdate) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM automation_runs WHERE id = :id AND workspace_id = :workspaceId"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", runId)
                .param("workspaceId", workspaceId)
                .query(AutomationService::runRow)
                .optional();
    }

    private List<RunView> views(WorkspaceContext workspace, List<RunRow> rows) {
        List<UUID> runIds = rows.stream().map(RunRow::id).toList();
        Map<UUID, SessionSummary> linkedSessions =
                sessions.summaries(workspace, rows.stream().map(RunRow::sessionId).distinct().toList());
        Map<UUID, UUID> createdTasks = tasks.tasksOfRuns(workspace, runIds);
        Map<UUID, JobView> latestJobs = jobs.latestBySubject(workspace.workspaceId(), runIds);
        return rows.stream()
                .map(row -> {
                    JobView job = latestJobs.get(row.id());
                    // While a run waits for a retry, its job shows the attempt count and the last problem.
                    Progress progress = row.status() == RunStatus.PENDING && job != null
                            ? new Progress(job.attempts(), job.maxAttempts(), job.lastError())
                            : null;
                    return new RunView(
                            row.id(),
                            row.ruleId(),
                            row.triggerStatus(),
                            row.taskTitle(),
                            row.dueDate(),
                            linkedSessions.get(row.sessionId()),
                            row.status(),
                            row.runCount(),
                            row.failureReason(),
                            progress,
                            createdTasks.get(row.id()),
                            row.triggeredAt(),
                            row.finishedAt());
                })
                .toList();
    }

    private static RuleView ruleView(ResultSet rs, int rowNum) throws SQLException {
        return new RuleView(
                rs.getObject("id", UUID.class),
                SessionStatus.valueOf(rs.getString("trigger_status")),
                rs.getString("task_title"),
                rs.getInt("due_in_days"),
                rs.getBoolean("enabled"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static RunRow runRow(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(
                rs.getObject("id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("client_id", UUID.class),
                SessionStatus.valueOf(rs.getString("trigger_status")),
                rs.getString("task_title"),
                rs.getObject("due_date", LocalDate.class),
                RunStatus.valueOf(rs.getString("status")),
                rs.getInt("run_count"),
                rs.getString("failure_reason"),
                instant(rs, "triggered_at"),
                instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record RunRow(
            UUID id,
            UUID ruleId,
            UUID sessionId,
            UUID clientId,
            SessionStatus triggerStatus,
            String taskTitle,
            LocalDate dueDate,
            RunStatus status,
            int runCount,
            String failureReason,
            Instant triggeredAt,
            Instant finishedAt) {}

    record RuleView(
            UUID id,
            SessionStatus triggerStatus,
            String taskTitle,
            int dueInDays,
            boolean enabled,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** The current attempt of a pending run's job, and the safe summary of its last failure. */
    record Progress(int attempts, int maxAttempts, String lastError) {}

    /**
     * A run as the API returns it. {@code triggerStatus}, {@code taskTitle}, and {@code dueDate} are what the rule
     * said when it fired. {@code taskId} is the created task, null until then or if the task was deleted.
     */
    record RunView(
            UUID id,
            UUID ruleId,
            SessionStatus triggerStatus,
            String taskTitle,
            LocalDate dueDate,
            SessionSummary session,
            RunStatus status,
            int runCount,
            String failureReason,
            Progress progress,
            UUID taskId,
            Instant triggeredAt,
            Instant finishedAt) {}
}
