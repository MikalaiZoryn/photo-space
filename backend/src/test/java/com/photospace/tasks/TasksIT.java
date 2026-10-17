package com.photospace.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Task CRUD, completion, filters, link validation, auditing, and that no user can reach another workspace's tasks. */
@IntegrationTest
class TasksIT {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String tasks;
    private String client;
    private String session;
    private String bobWorkspace;
    private String bobTasks;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        String base = "/api/workspaces/" + aliceWorkspace;
        tasks = base + "/tasks";
        client = alice.post(base + "/clients", "{\"name\":\"Anna Nowak\"}").json("$.id");
        session = alice.post(base + "/sessions", "{\"title\":\"Family\",\"clientId\":\"%s\"}".formatted(client)).json("$.id");
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
        bobTasks = "/api/workspaces/" + bobWorkspace + "/tasks";
    }

    @Test
    void createsUpdatesCompletesAndDeletesATask() {
        String me = alice.get("/api/me").json("$.id");
        Response created = alice.post(tasks, """
                {"title":"  Send the contract ","description":"  ","dueDate":"2026-11-07","clientId":"%s",
                 "sessionId":"%s","assigneeId":"%s","status":"DONE","source":"AUTOMATION"}""".formatted(client, session, me));

        assertThat(created.status()).isEqualTo(201);
        String id = created.json("$.id");
        assertThat(created.<String>json("$.title")).isEqualTo("Send the contract");
        assertThat(created.<Object>json("$.description")).isNull();
        assertThat(created.<String>json("$.dueDate")).isEqualTo("2026-11-07");
        assertThat(created.<String>json("$.status")).as("status is not settable on create").isEqualTo("OPEN");
        assertThat(created.<String>json("$.source")).isEqualTo("MANUAL");
        assertThat(created.<String>json("$.client.name")).isEqualTo("Anna Nowak");
        assertThat(created.<String>json("$.session.title")).isEqualTo("Family");
        assertThat(created.<String>json("$.assignee.userId")).isEqualTo(me);
        assertThat(created.<Integer>json("$.version")).isZero();

        Response updated = alice.put(tasks + "/" + id, """
                {"version":0,"task":{"title":"Send the signed contract","dueDate":null}}""");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.<String>json("$.title")).isEqualTo("Send the signed contract");
        assertThat(updated.<Object>json("$.client")).as("links are replaced, not merged").isNull();
        assertThat(updated.<Integer>json("$.version")).isEqualTo(1);
        assertThat(alice.put(tasks + "/" + id, "{\"version\":0,\"task\":{\"title\":\"Stale\"}}").status()).isEqualTo(409);

        Response done = alice.post(tasks + "/" + id + "/status", "{\"status\":\"DONE\"}");
        assertThat(done.<String>json("$.status")).isEqualTo("DONE");
        assertThat(done.<String>json("$.completedAt")).isNotNull();
        Response doneAgain = alice.post(tasks + "/" + id + "/status", "{\"status\":\"DONE\"}");
        assertThat(doneAgain.<Integer>json("$.version")).as("idempotent").isEqualTo(done.<Integer>json("$.version"));
        Response reopened = alice.post(tasks + "/" + id + "/status", "{\"status\":\"OPEN\"}");
        assertThat(reopened.<String>json("$.status")).isEqualTo("OPEN");
        assertThat(reopened.<Object>json("$.completedAt")).isNull();

        assertThat(alice.delete(tasks + "/" + id).status()).isEqualTo(204);
        assertThat(alice.get(tasks + "/" + id).status()).isEqualTo(404);
        assertThat(alice.delete(tasks + "/" + id).status()).isEqualTo(404);

        assertThat(jdbc.queryForList("""
                        SELECT action FROM audit_events WHERE target_id = ?::uuid AND actor_id = ?::uuid
                         ORDER BY created_at, id""", String.class, id, me))
                .containsExactly("TASK_CREATED", "TASK_UPDATED", "TASK_COMPLETED", "TASK_REOPENED", "TASK_DELETED");
    }

    @Test
    void filtersByStatusLinkAssigneeAndOverdueInTheWorkspaceZone() {
        String me = alice.get("/api/me").json("$.id");
        LocalDate today = LocalDate.now(WARSAW);
        String overdue = create("{\"title\":\"Overdue\",\"dueDate\":\"%s\",\"sessionId\":\"%s\"}"
                .formatted(today.minusDays(1), session));
        String dueToday = create("{\"title\":\"Today\",\"dueDate\":\"%s\",\"assigneeId\":\"%s\"}".formatted(today, me));
        String undated = create("{\"title\":\"Someday\",\"clientId\":\"%s\"}".formatted(client));
        String finished = create("{\"title\":\"Finished\",\"dueDate\":\"%s\"}".formatted(today.minusDays(5)));
        alice.post(tasks + "/" + finished + "/status", "{\"status\":\"DONE\"}");

        assertThat(ids(tasks)).as("open first, by due date, undated last").containsExactly(overdue, dueToday, undated, finished);
        assertThat(ids(tasks + "?status=OPEN")).containsExactly(overdue, dueToday, undated);
        assertThat(ids(tasks + "?status=DONE")).containsExactly(finished);
        assertThat(ids(tasks + "?overdue=true")).as("done tasks are never overdue").containsExactly(overdue);
        assertThat(ids(tasks + "?sessionId=" + session)).containsExactly(overdue);
        assertThat(ids(tasks + "?clientId=" + client)).containsExactly(undated);
        assertThat(ids(tasks + "?assigneeId=" + me)).containsExactly(dueToday);
        assertThat(alice.get(tasks + "/" + overdue).<Boolean>json("$.overdue")).isTrue();
        assertThat(alice.get(tasks + "/" + dueToday).<Boolean>json("$.overdue")).isFalse();
        assertThat(alice.get(tasks + "?size=2").<Integer>json("$.totalItems")).isEqualTo(4);
        assertThat(alice.get(tasks + "?status=LATER").status()).isEqualTo(400);
    }

    @Test
    void validatesFieldsAndLinks() {
        assertThat(alice.post(tasks, "{\"title\":\" \"}").<String>json("$.errors.title")).isNotNull();
        assertThat(alice.post(tasks, "{\"title\":\"%s\"}".formatted("a".repeat(201))).status()).isEqualTo(400);
        assertThat(alice.post(tasks, "{\"title\":\"x\",\"dueDate\":\"07.11.2026\"}").status()).isEqualTo(400);

        String bobId = bob.get("/api/me").json("$.id");
        String bobClient = bob.post("/api/workspaces/" + bobWorkspace + "/clients", "{\"name\":\"B\"}").json("$.id");
        String bobSession = bob.post("/api/workspaces/" + bobWorkspace + "/sessions", "{\"title\":\"B\"}").json("$.id");
        Response foreign = alice.post(tasks, """
                {"title":"x","clientId":"%s","sessionId":"%s","assigneeId":"%s"}""".formatted(bobClient, bobSession, bobId));
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.<String>json("$.errors.clientId")).isEqualTo("does not match a client in this workspace");
        assertThat(foreign.<String>json("$.errors.sessionId")).isEqualTo("does not match a session in this workspace");
        assertThat(foreign.<String>json("$.errors.assigneeId")).isEqualTo("is not a member of this workspace");

        String linked = create("{\"title\":\"Linked\",\"sessionId\":\"%s\"}".formatted(session));
        alice.post("/api/workspaces/" + aliceWorkspace + "/sessions/" + session + "/archive");
        assertThat(alice.post(tasks, "{\"title\":\"x\",\"sessionId\":\"%s\"}".formatted(session))
                .<String>json("$.errors.sessionId")).isEqualTo("is archived; restore the session first");
        assertThat(alice.put(tasks + "/" + linked, "{\"version\":0,\"task\":{\"title\":\"Kept\",\"sessionId\":\"%s\"}}"
                .formatted(session)).status()).as("an existing link to an archived session is kept").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE workspace_id = ?::uuid", Long.class,
                aliceWorkspace)).isEqualTo(1);
    }

    @Test
    void membersCanManageTasksAndBeAssigned() {
        String bobId = bob.get("/api/me").json("$.id");
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, bobId);

        Response created = bob.post(tasks, "{\"title\":\"Edit photos\",\"assigneeId\":\"%s\"}".formatted(bobId));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.assignee.userId")).isEqualTo(bobId);
        assertThat(bob.post(tasks + "/" + created.json("$.id") + "/status", "{\"status\":\"DONE\"}").status()).isEqualTo(200);

        jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ?::uuid AND user_id = ?::uuid", aliceWorkspace, bobId);
        assertThat(alice.get(tasks + "/" + created.json("$.id")).<Object>json("$.assignee"))
                .as("removing a member unassigns their tasks").isNull();
    }

    @Test
    void otherWorkspacesCannotSeeOrChangeATask() {
        String id = create("{\"title\":\"Private\"}");
        String foreignPath = bobTasks + "/" + id;

        assertThat(bob.get(foreignPath).status()).isEqualTo(404);
        assertThat(bob.put(foreignPath, "{\"version\":0,\"task\":{\"title\":\"Hijacked\"}}").status()).isEqualTo(404);
        assertThat(bob.post(foreignPath + "/status", "{\"status\":\"DONE\"}").status()).isEqualTo(404);
        assertThat(bob.delete(foreignPath).status()).isEqualTo(404);
        assertThat(bob.get(bobTasks).<Integer>json("$.totalItems")).isZero();
        for (String path : List.of(tasks, tasks + "/" + id)) {
            assertThat(bob.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(bob.post(tasks, "{\"title\":\"x\"}").status()).isEqualTo(404);
        assertThat(bob.delete(tasks + "/" + id).status()).isEqualTo(404);

        Response unchanged = alice.get(tasks + "/" + id);
        assertThat(unchanged.<String>json("$.title")).isEqualTo("Private");
        assertThat(unchanged.<String>json("$.status")).isEqualTo("OPEN");
        assertThat(unchanged.<Integer>json("$.version")).isZero();
    }

    private String create(String json) {
        Response response = alice.post(tasks, json);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json("$.id");
    }

    private List<String> ids(String path) {
        return alice.get(path).json("$.items[*].id");
    }
}
