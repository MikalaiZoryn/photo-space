package com.photospace.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.sessions.SessionStatus;
import com.photospace.sessions.SessionStatusChanged;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automation rules end to end: a session status change fires the matching enabled rules once per session, the job
 * creates exactly one task even when the event or the job is delivered again, failures are visible and can be
 * retried, outcomes are audited, and rules, runs, jobs, and audit events stay inside their workspace.
 */
@IntegrationTest
class AutomationIT {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String base;
    private String client;
    private String bobWorkspace;
    private String bobBase;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        base = "/api/workspaces/" + aliceWorkspace;
        client = alice.post(base + "/clients", "{\"name\":\"Anna Nowak\"}").json("$.id");
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
        bobBase = "/api/workspaces/" + bobWorkspace;
    }

    @Test
    void aStatusChangeCreatesTheConfiguredTaskOnceEvenIfDeliveredAgain() {
        LocalDate before = LocalDate.now(WARSAW);
        String rule = createRule(alice, base, "COMPLETED", "Send the gallery", 3, true);
        String session = bookedSession();
        assertThat(runs(base)).as("BOOKED does not match the rule").isEmpty();

        changeStatus(session, "COMPLETED");
        List<String> runIds = runs(base);
        assertThat(runIds).as("the run is recorded with the status change").hasSize(1);
        String run = runIds.getFirst();
        Response succeeded = awaitRun(run, "SUCCEEDED");
        LocalDate after = LocalDate.now(WARSAW);

        String taskId = succeeded.json("$.items[0].taskId");
        assertThat(succeeded.<String>json("$.items[0].ruleId")).isEqualTo(rule);
        assertThat(succeeded.<String>json("$.items[0].session.id")).isEqualTo(session);
        Response task = alice.get(base + "/tasks/" + taskId);
        assertThat(task.<String>json("$.title")).isEqualTo("Send the gallery");
        assertThat(task.<String>json("$.source")).isEqualTo("AUTOMATION");
        assertThat(task.<String>json("$.status")).isEqualTo("OPEN");
        assertThat(task.<String>json("$.session.id")).isEqualTo(session);
        assertThat(task.<String>json("$.client.id")).isEqualTo(client);
        assertThat(LocalDate.parse(task.json("$.dueDate"))).isIn(before.plusDays(3), after.plusDays(3));

        // The same status again is a no-op, and a duplicate event finds the run already there.
        changeStatus(session, "COMPLETED");
        transaction.executeWithoutResult(status -> events.publishEvent(new SessionStatusChanged(
                UUID.fromString(aliceWorkspace), UUID.fromString(session), UUID.fromString(client),
                SessionStatus.BOOKED, SessionStatus.COMPLETED, null, Instant.now())));
        assertThat(runs(base)).containsExactly(run);

        // A duplicate job delivery runs the handler again, which finds the run finished.
        jdbc.update("""
                UPDATE background_jobs SET status = 'QUEUED', dispatched_at = NULL, finished_at = NULL
                 WHERE subject_id = ?::uuid""", run);
        Eventually.await(() -> jdbc.queryForList(
                "SELECT attempts FROM background_jobs WHERE subject_id = ?::uuid AND status = 'SUCCEEDED'",
                Integer.class, run), attempts -> attempts.equals(List.of(2)));
        assertThat(taskCount()).isEqualTo(1);

        assertThat(jdbc.queryForList("""
                        SELECT action FROM audit_events
                         WHERE workspace_id = ?::uuid AND actor_id IS NULL ORDER BY created_at, id""",
                String.class, aliceWorkspace))
                .as("the system's own changes are audited without an actor, in one transaction")
                .containsExactlyInAnyOrder("TASK_CREATED", "AUTOMATION_RUN_SUCCEEDED");
    }

    @Test
    void onlyEnabledRulesFireAndEachAtMostOncePerSession() {
        createRule(alice, base, "BOOKED", "Send the contract", 0, true);
        createRule(alice, base, "CANCELLED", "Refund the deposit", 0, false);
        String session = bookedSession();
        assertThat(runs(base)).hasSize(1);

        changeStatus(session, "CANCELLED");
        assertThat(runs(base)).as("the CANCELLED rule is disabled").hasSize(1);
        changeStatus(session, "INQUIRY");
        changeStatus(session, "BOOKED");
        assertThat(runs(base)).as("booking the same session again does not fire the rule twice").hasSize(1);

        awaitRun(runs(base).getFirst(), "SUCCEEDED");
        assertThat(taskCount()).isEqualTo(1);
    }

    @Test
    void failedRunsAreVisibleAndCanBeRetried() {
        String title = "Fail me " + UUID.randomUUID();
        jdbc.execute("""
                CREATE FUNCTION test_fail_task() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.title LIKE 'Fail me %' THEN RAISE EXCEPTION 'test failure'; END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("CREATE TRIGGER test_fail_task BEFORE INSERT ON tasks FOR EACH ROW EXECUTE FUNCTION test_fail_task()");
        String run;
        try {
            createRule(alice, base, "BOOKED", title, 1, true);
            bookedSession();
            run = runs(base).getFirst();
            Response failed = awaitRun(run, "FAILED");
            assertThat(failed.<String>json("$.items[0].failureReason")).isEqualTo(RunAutomationJob.GAVE_UP);
            assertThat(failed.<Object>json("$.items[0].taskId")).isNull();
            assertThat(alice.get(base + "/automation/runs?status=FAILED").<List<String>>json("$.items[*].id"))
                    .containsExactly(run);

            Response jobs = alice.get(base + "/jobs?status=FAILED");
            assertThat(jobs.<List<String>>json("$.items[*].subjectId")).containsExactly(run);
            assertThat(jobs.<String>json("$.items[0].type")).isEqualTo("run-automation");
            assertThat(jobs.<Integer>json("$.items[0].attempts")).isEqualTo(3);
            assertThat(jobs.<String>json("$.items[0].lastError")).isNotBlank().doesNotContain("test failure");
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_task ON tasks");
            jdbc.execute("DROP FUNCTION test_fail_task()");
        }

        Response retried = alice.post(base + "/automation/runs/" + run + "/retry");
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.<String>json("$.status")).isEqualTo("PENDING");
        assertThat(retried.<Integer>json("$.runCount")).isEqualTo(2);
        assertThat(retried.<Object>json("$.failureReason")).isNull();
        Response succeeded = awaitRun(run, "SUCCEEDED");
        assertThat(alice.get(base + "/tasks/" + succeeded.json("$.items[0].taskId")).<String>json("$.title"))
                .isEqualTo(title);
        assertThat(alice.post(base + "/automation/runs/" + run + "/retry").status())
                .as("only a failed run can be retried").isEqualTo(409);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE target_id = ?::uuid ORDER BY created_at, id",
                String.class, run))
                .containsExactly("AUTOMATION_RUN_FAILED", "AUTOMATION_RUN_RETRIED", "AUTOMATION_RUN_SUCCEEDED");
    }

    @Test
    void ownersManageRulesAndTheAuditLog() {
        String rule = createRule(alice, base, "DELIVERED", "Ask for a review", 7, true);
        Response updated = alice.put(base + "/automation/rules/" + rule, """
                {"version":0,"rule":{"triggerStatus":"DELIVERED","taskTitle":"Ask for a review","dueInDays":14,"enabled":false}}""");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.<Integer>json("$.dueInDays")).isEqualTo(14);
        assertThat(updated.<Boolean>json("$.enabled")).isFalse();
        assertThat(alice.put(base + "/automation/rules/" + rule, ruleJson("DELIVERED", "Stale", 1, true, 0)).status())
                .isEqualTo(409);

        Response inquiry = alice.post(base + "/automation/rules", ruleJson("INQUIRY", "x", 0, true, null));
        assertThat(inquiry.<String>json("$.errors.triggerStatus")).isEqualTo("must be BOOKED, COMPLETED, DELIVERED, or CANCELLED");
        assertThat(alice.post(base + "/automation/rules", ruleJson("BOOKED", "x", 366, true, null)).status()).isEqualTo(400);
        assertThat(alice.post(base + "/automation/rules", ruleJson("BOOKED", " ", 1, true, null)).status()).isEqualTo(400);
        assertThat(alice.post(base + "/automation/rules", "{\"triggerStatus\":\"BOOKED\",\"taskTitle\":\"x\"}").status())
                .isEqualTo(400);

        Response log = alice.get(base + "/audit-events");
        assertThat(log.<List<String>>json("$.items[*].action"))
                .containsExactly("AUTOMATION_RULE_UPDATED", "AUTOMATION_RULE_CREATED");
        assertThat(log.<String>json("$.items[0].detail")).isEqualTo("DELIVERED, due in 14 days, disabled");
        assertThat(log.<String>json("$.items[0].actor.userId")).isEqualTo(alice.get("/api/me").json("$.id"));
        assertThat(log.<String>json("$.items[0].correlationId")).isNotBlank();
        assertThat(alice.get(base + "/audit-events?targetId=" + UUID.randomUUID()).<Integer>json("$.totalItems")).isZero();

        String bobId = bob.get("/api/me").json("$.id");
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, bobId);
        assertThat(bob.get(base + "/automation/rules").<List<String>>json("$[*].id")).containsExactly(rule);
        assertThat(bob.get(base + "/automation/runs").status()).isEqualTo(200);
        assertThat(bob.post(base + "/automation/rules", ruleJson("BOOKED", "x", 1, true, null)).status()).isEqualTo(403);
        assertThat(bob.put(base + "/automation/rules/" + rule, ruleJson("BOOKED", "x", 1, true, 1)).status()).isEqualTo(403);
        assertThat(bob.get(base + "/audit-events").status()).isEqualTo(403);
        assertThat(bob.get(base + "/jobs").status()).isEqualTo(403);
        assertThat(alice.get(base + "/automation/rules").<Integer>json("$[0].version")).isEqualTo(1);
    }

    @Test
    void otherWorkspacesCannotSeeOrTriggerRulesRunsJobsOrAuditEvents() {
        String rule = createRule(alice, base, "BOOKED", "Send the contract", 0, true);
        bookedSession();
        String run = runs(base).getFirst();
        awaitRun(run, "SUCCEEDED");

        String foreignBase = "/api/workspaces/" + aliceWorkspace;
        assertThat(bob.get(foreignBase + "/automation/rules").status()).isEqualTo(404);
        assertThat(bob.get(foreignBase + "/automation/runs").status()).isEqualTo(404);
        assertThat(bob.get(foreignBase + "/jobs").status()).isEqualTo(404);
        assertThat(bob.get(foreignBase + "/audit-events").status()).isEqualTo(404);
        assertThat(bob.post(foreignBase + "/automation/runs/" + run + "/retry").status()).isEqualTo(404);
        assertThat(bob.put(bobBase + "/automation/rules/" + rule, ruleJson("BOOKED", "x", 1, false, 0)).status())
                .isEqualTo(404);
        assertThat(bob.post(bobBase + "/automation/runs/" + run + "/retry").status()).isEqualTo(404);
        assertThat(bob.get(bobBase + "/automation/rules").body()).isEqualTo("[]");
        assertThat(bob.get(bobBase + "/jobs").<Integer>json("$.totalItems")).isZero();
        assertThat(bob.get(bobBase + "/audit-events?targetId=" + run).<Integer>json("$.totalItems")).isZero();

        // Bob's own status changes never fire Alice's rules.
        String bobSession = bob.post(bobBase + "/sessions", "{\"title\":\"B\",\"startLocal\":\"2026-11-07T10:00\"}").json("$.id");
        assertThat(bob.post(bobBase + "/sessions/" + bobSession + "/status", "{\"status\":\"BOOKED\"}").status()).isEqualTo(200);
        assertThat(runs(bobBase, bob)).isEmpty();
        assertThat(runs(base)).containsExactly(run);
        assertThat(alice.get(base + "/automation/rules").<Boolean>json("$[0].enabled")).isTrue();
    }

    private String bookedSession() {
        Response created = alice.post(base + "/sessions", """
                {"title":"Family","clientId":"%s","startLocal":"2026-11-07T10:00"}""".formatted(client));
        assertThat(created.status()).isEqualTo(201);
        String session = created.json("$.id");
        changeStatus(session, "BOOKED");
        return session;
    }

    private void changeStatus(String session, String status) {
        Response response = alice.post(base + "/sessions/" + session + "/status", "{\"status\":\"" + status + "\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    private Response awaitRun(String run, String status) {
        return Eventually.await(() -> alice.get(base + "/automation/runs?size=1"),
                response -> run.equals(response.json("$.items[0].id")) && status.equals(response.json("$.items[0].status")));
    }

    private List<String> runs(String workspaceBase) {
        return runs(workspaceBase, alice);
    }

    private static List<String> runs(String workspaceBase, ApiClient user) {
        return user.get(workspaceBase + "/automation/runs").json("$.items[*].id");
    }

    private long taskCount() {
        return jdbc.queryForObject("SELECT count(*) FROM tasks WHERE workspace_id = ?::uuid", Long.class, aliceWorkspace);
    }

    private static String createRule(ApiClient user, String workspaceBase, String trigger, String title, int dueInDays,
            boolean enabled) {
        Response created = user.post(workspaceBase + "/automation/rules", ruleJson(trigger, title, dueInDays, enabled, null));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json("$.id");
    }

    private static String ruleJson(String trigger, String title, int dueInDays, boolean enabled, Integer version) {
        String rule = "{\"triggerStatus\":\"%s\",\"taskTitle\":\"%s\",\"dueInDays\":%d,\"enabled\":%b}"
                .formatted(trigger, title, dueInDays, enabled);
        return version == null ? rule : "{\"version\":%d,\"rule\":%s}".formatted(version, rule);
    }
}
