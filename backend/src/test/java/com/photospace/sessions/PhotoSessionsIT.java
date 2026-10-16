package com.photospace.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session CRUD, time zones, the status lifecycle, client links, archiving, paging, and that no user can reach
 * another workspace's sessions or link another workspace's clients.
 */
@IntegrationTest
class PhotoSessionsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String aliceSessions;
    private String aliceClients;
    private String bobWorkspace;
    private String bobSessions;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        aliceSessions = "/api/workspaces/" + aliceWorkspace + "/sessions";
        aliceClients = "/api/workspaces/" + aliceWorkspace + "/clients";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
        bobSessions = "/api/workspaces/" + bobWorkspace + "/sessions";
    }

    @Test
    void createsAnInquiryWithAClientAndTimesInTheWorkspaceZone() {
        String clientId = alice.post(aliceClients, "{\"name\":\"Anna Nowak\"}").json("$.id");

        Response created = alice.post(aliceSessions, """
                {"title":" Family session ","sessionType":"Family","clientId":"%s",
                 "startLocal":"2026-07-04T10:00","endLocal":"2026-07-04T12:30",
                 "location":"Łazienki Park","notes":"Bring the dog","status":"DELIVERED"}""".formatted(clientId));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.title")).isEqualTo("Family session");
        assertThat(created.<String>json("$.sessionType")).isEqualTo("Family");
        assertThat(created.<String>json("$.status")).as("always starts as an inquiry").isEqualTo("INQUIRY");
        assertThat(created.<List<String>>json("$.allowedStatuses")).containsExactly("BOOKED", "CANCELLED");
        assertThat(created.<String>json("$.client.id")).isEqualTo(clientId);
        assertThat(created.<String>json("$.client.name")).isEqualTo("Anna Nowak");
        assertThat(created.<Boolean>json("$.client.archived")).isFalse();
        assertThat(created.<String>json("$.startsAt")).isEqualTo("2026-07-04T08:00:00Z");
        assertThat(created.<String>json("$.endsAt")).isEqualTo("2026-07-04T10:30:00Z");
        assertThat(created.<String>json("$.startLocal")).isEqualTo("2026-07-04T10:00:00");
        assertThat(created.<String>json("$.endLocal")).isEqualTo("2026-07-04T12:30:00");
        assertThat(created.<String>json("$.timezone")).isEqualTo("Europe/Warsaw");
        assertThat(created.<String>json("$.location")).isEqualTo("Łazienki Park");

        String stored = jdbc.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') FROM photo_sessions WHERE id = ?::uuid",
                String.class, created.<String>json("$.id"));
        assertThat(stored).isEqualTo("2026-07-04 08:00");
    }

    @Test
    void keepsTheInstantWhenTheWorkspaceZoneChanges() {
        String id = alice.post(aliceSessions, session("Shoot", "2026-07-04T10:00")).json("$.id");

        alice.put("/api/workspaces/" + aliceWorkspace, "{\"name\":\"Alice Studio\",\"timezone\":\"America/New_York\"}");

        Response moved = alice.get(aliceSessions + "/" + id);
        assertThat(moved.<String>json("$.startsAt")).isEqualTo("2026-07-04T08:00:00Z");
        assertThat(moved.<String>json("$.startLocal")).isEqualTo("2026-07-04T04:00:00");
        assertThat(moved.<String>json("$.timezone")).isEqualTo("America/New_York");
    }

    @Test
    void createsAnUnscheduledSessionWithOnlyATitle() {
        Response created = alice.post(aliceSessions, "{\"title\":\"Maybe a wedding\"}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<Object>json("$.startsAt")).isNull();
        assertThat(created.<Object>json("$.startLocal")).isNull();
        assertThat(created.<Object>json("$.client")).isNull();
    }

    @Test
    void rejectsInvalidFieldsAndSchedules() {
        Response blank = alice.post(aliceSessions, "{\"title\":\"  \",\"location\":\"%s\"}".formatted("x".repeat(301)));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.<Map<String, String>>json("$.errors")).containsOnlyKeys("title", "location");

        Response backwards = alice.post(aliceSessions, """
                {"title":"Shoot","startLocal":"2026-07-04T12:00","endLocal":"2026-07-04T11:00"}""");
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(backwards.<String>json("$.errors.endLocal")).isEqualTo("must be after the start");

        Response endOnly = alice.post(aliceSessions, "{\"title\":\"Shoot\",\"endLocal\":\"2026-07-04T11:00\"}");
        assertThat(endOnly.<String>json("$.errors.startLocal")).isNotBlank();

        Response gap = alice.post(aliceSessions, session("Shoot", "2027-03-28T02:30"));
        assertThat(gap.status()).isEqualTo(400);
        assertThat(gap.<String>json("$.errors.startLocal")).contains("clocks change");

        assertThat(alice.post(aliceSessions, session("Shoot", "not-a-date")).status()).isEqualTo(400);
        assertThat(alice.post(aliceSessions, session("Shoot", "2026-07-04T10:00:00+02:00")).status())
                .as("offsets are not accepted; times are wall-clock in the workspace zone")
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_sessions WHERE workspace_id = ?::uuid",
                Integer.class, aliceWorkspace)).isZero();
    }

    @Test
    void followsTheLifecycleFromInquiryToDelivered() {
        Response created = alice.post(aliceSessions, "{\"title\":\"Wedding\"}");
        String id = created.json("$.id");

        Response noDate = status(id, "BOOKED");
        assertThat(noDate.status()).isEqualTo(409);
        assertThat(noDate.<String>json("$.detail")).isEqualTo("Set a start time before changing the session to BOOKED.");

        alice.put(aliceSessions + "/" + id, update(0, session("Wedding", "2026-09-12T15:00")));
        assertThat(status(id, "COMPLETED").status()).as("cannot skip booking").isEqualTo(409);

        Response booked = status(id, "BOOKED");
        assertThat(booked.status()).isEqualTo(200);
        assertThat(booked.<String>json("$.status")).isEqualTo("BOOKED");
        assertThat(booked.<List<String>>json("$.allowedStatuses")).containsExactly("COMPLETED", "CANCELLED");
        assertThat(status(id, "BOOKED").status()).as("repeating a change is harmless").isEqualTo(200);

        assertThat(status(id, "COMPLETED").<String>json("$.status")).isEqualTo("COMPLETED");
        Response delivered = status(id, "DELIVERED");
        assertThat(delivered.<String>json("$.status")).isEqualTo("DELIVERED");
        assertThat(delivered.<List<String>>json("$.allowedStatuses")).isEmpty();

        Response backwards = status(id, "INQUIRY");
        assertThat(backwards.status()).isEqualTo(409);
        assertThat(backwards.<String>json("$.detail")).isEqualTo("A session cannot change from DELIVERED to INQUIRY.");
    }

    @Test
    void cancelsAndReopensAnInquiry() {
        String id = alice.post(aliceSessions, "{\"title\":\"Maybe\"}").json("$.id");

        assertThat(status(id, "CANCELLED").<List<String>>json("$.allowedStatuses")).containsExactly("INQUIRY");
        assertThat(status(id, "BOOKED").status()).isEqualTo(409);
        assertThat(status(id, "INQUIRY").<String>json("$.status")).isEqualTo("INQUIRY");
    }

    @Test
    void rejectsInvalidStatusRequests() {
        String id = alice.post(aliceSessions, "{\"title\":\"Shoot\"}").json("$.id");

        assertThat(alice.post(aliceSessions + "/" + id + "/status", "{\"status\":\"PUBLISHED\"}").status())
                .isEqualTo(400);
        assertThat(alice.post(aliceSessions + "/" + id + "/status", "{}").<String>json("$.errors.status"))
                .isNotBlank();
    }

    @Test
    void aBookedSessionKeepsItsStart() {
        String id = alice.post(aliceSessions, session("Shoot", "2026-07-04T10:00")).json("$.id");
        int version = status(id, "BOOKED").json("$.version");

        Response cleared = alice.put(aliceSessions + "/" + id, update(version, "{\"title\":\"Shoot\"}"));

        assertThat(cleared.status()).isEqualTo(400);
        assertThat(cleared.<String>json("$.errors.startLocal")).isEqualTo("is required once a session is booked");
        Response moved = alice.put(aliceSessions + "/" + id, update(version, session("Shoot", "2026-07-05T09:00")));
        assertThat(moved.<String>json("$.startLocal")).isEqualTo("2026-07-05T09:00:00");
        assertThat(moved.<String>json("$.status")).as("editing never changes status").isEqualTo("BOOKED");
    }

    @Test
    void rejectsAStaleVersion() {
        String id = alice.post(aliceSessions, "{\"title\":\"Shoot\"}").json("$.id");
        alice.put(aliceSessions + "/" + id, update(0, "{\"title\":\"First\"}"));

        Response stale = alice.put(aliceSessions + "/" + id, update(0, "{\"title\":\"Second\"}"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.<String>json("$.detail")).contains("changed by someone else");
        assertThat(alice.get(aliceSessions + "/" + id).<String>json("$.title")).isEqualTo("First");
    }

    @Test
    void linksOnlyActiveClientsOfTheSameWorkspace() {
        String foreignClient = bob.post("/api/workspaces/" + bobWorkspace + "/clients", "{\"name\":\"Bob's client\"}")
                .json("$.id");

        Response foreign = alice.post(aliceSessions, withClient(foreignClient));
        Response unknown = alice.post(aliceSessions, withClient(UUID.randomUUID().toString()));
        for (Response response : List.of(foreign, unknown)) {
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.<String>json("$.errors.clientId")).isEqualTo("does not match a client in this workspace");
        }

        String archivedClient = alice.post(aliceClients, "{\"name\":\"Old client\"}").json("$.id");
        alice.post(aliceClients + "/" + archivedClient + "/archive");
        assertThat(alice.post(aliceSessions, withClient(archivedClient)).<String>json("$.errors.clientId"))
                .contains("archived");
    }

    @Test
    void keepsTheLinkToAClientArchivedLater() {
        String clientId = alice.post(aliceClients, "{\"name\":\"Anna\"}").json("$.id");
        String id = alice.post(aliceSessions, withClient(clientId)).json("$.id");
        alice.post(aliceClients + "/" + clientId + "/archive");

        Response session = alice.get(aliceSessions + "/" + id);
        assertThat(session.<Boolean>json("$.client.archived")).isTrue();

        Response edited = alice.put(aliceSessions + "/" + id, update(0, """
                {"title":"Renamed","clientId":"%s"}""".formatted(clientId)));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.<String>json("$.client.id")).isEqualTo(clientId);

        Response unlinked = alice.put(aliceSessions + "/" + id, update(1, "{\"title\":\"Renamed\"}"));
        assertThat(unlinked.<Object>json("$.client")).isNull();
    }

    @Test
    void archivesAndRestores() {
        String id = alice.post(aliceSessions, "{\"title\":\"Shoot\"}").json("$.id");

        Response archived = alice.post(aliceSessions + "/" + id + "/archive");
        assertThat(archived.<String>json("$.archivedAt")).isNotBlank();
        assertThat(archived.<List<String>>json("$.allowedStatuses")).isEmpty();
        assertThat(alice.get(aliceSessions).<List<String>>json("$.items[*].id")).doesNotContain(id);
        assertThat(alice.get(aliceSessions + "?archived=true").<List<String>>json("$.items[*].id")).containsExactly(id);
        assertThat(alice.put(aliceSessions + "/" + id, update(archived.json("$.version"), "{\"title\":\"X\"}")).status())
                .isEqualTo(409);
        assertThat(status(id, "CANCELLED").status()).isEqualTo(409);

        Response restored = alice.post(aliceSessions + "/" + id + "/restore");
        assertThat(restored.<Object>json("$.archivedAt")).isNull();
        assertThat(status(id, "CANCELLED").status()).isEqualTo(200);
    }

    @Test
    void listsUnscheduledFirstThenNewestAndFiltersByStatusAndClient() {
        String clientId = alice.post(aliceClients, "{\"name\":\"Anna\"}").json("$.id");
        alice.post(aliceSessions, session("June", "2026-06-01T10:00"));
        String august = alice.post(aliceSessions, """
                {"title":"August","startLocal":"2026-08-01T10:00","clientId":"%s"}""".formatted(clientId)).json("$.id");
        alice.post(aliceSessions, "{\"title\":\"Unscheduled\"}");
        alice.post(aliceSessions, session("July", "2026-07-01T10:00"));
        status(august, "BOOKED");

        Response all = alice.get(aliceSessions);
        assertThat(all.<List<String>>json("$.items[*].title")).containsExactly("Unscheduled", "August", "July", "June");
        assertThat(all.<String>json("$.items[1].client.name")).isEqualTo("Anna");

        Response page = alice.get(aliceSessions + "?size=3&page=1");
        assertThat(page.<List<String>>json("$.items[*].title")).containsExactly("June");
        assertThat(page.<Integer>json("$.totalItems")).isEqualTo(4);

        assertThat(alice.get(aliceSessions + "?status=BOOKED").<List<String>>json("$.items[*].title"))
                .containsExactly("August");
        assertThat(alice.get(aliceSessions + "?clientId=" + clientId).<List<String>>json("$.items[*].title"))
                .containsExactly("August");
        assertThat(alice.get(aliceSessions + "?status=PUBLISHED").<String>json("$.errors.status")).isNotBlank();
        assertThat(alice.get(aliceSessions + "?clientId=nope").<String>json("$.errors.clientId")).isNotBlank();
        assertThat(alice.get(aliceSessions + "?size=500").<String>json("$.errors.size")).isNotBlank();
    }

    @Test
    void otherWorkspacesCannotReadListOrChangeTheSession() {
        String clientId = alice.post(aliceClients, "{\"name\":\"Anna\"}").json("$.id");
        String id = alice.post(aliceSessions, withClient(clientId)).json("$.id");

        // Through Bob's own workspace, where the membership check passes but the session is not his.
        assertThat(bob.get(bobSessions + "/" + id).status()).isEqualTo(404);
        assertThat(bob.put(bobSessions + "/" + id, update(0, "{\"title\":\"Hijacked\"}")).status()).isEqualTo(404);
        assertThat(bob.post(bobSessions + "/" + id + "/status", "{\"status\":\"CANCELLED\"}").status()).isEqualTo(404);
        assertThat(bob.post(bobSessions + "/" + id + "/archive").status()).isEqualTo(404);
        assertThat(bob.post(bobSessions + "/" + id + "/restore").status()).isEqualTo(404);
        assertThat(bob.get(bobSessions).<List<String>>json("$.items[*].id")).doesNotContain(id);
        assertThat(bob.get(bobSessions + "?clientId=" + clientId).<Integer>json("$.totalItems")).isZero();

        // Through Alice's workspace, where the membership check itself refuses.
        assertThat(bob.get(aliceSessions).status()).isEqualTo(404);
        assertThat(bob.get(aliceSessions + "/" + id).status()).isEqualTo(404);
        assertThat(bob.post(aliceSessions, "{\"title\":\"Planted\"}").status()).isEqualTo(404);
        assertThat(bob.post(aliceSessions + "/" + id + "/status", "{\"status\":\"CANCELLED\"}").status())
                .isEqualTo(404);

        Response unchanged = alice.get(aliceSessions + "/" + id);
        assertThat(unchanged.<String>json("$.title")).isEqualTo("Shoot");
        assertThat(unchanged.<String>json("$.status")).isEqualTo("INQUIRY");
        assertThat(unchanged.<Object>json("$.archivedAt")).isNull();
    }

    @Test
    void unknownForeignAndMalformedSessionIdsAreIndistinguishable() {
        String foreign = bob.post(bobSessions, "{\"title\":\"Bob's session\"}").json("$.id");

        for (String id : List.of(foreign, UUID.randomUUID().toString(), "not-a-uuid")) {
            Response response = alice.get(aliceSessions + "/" + id);
            assertThat(response.status()).as(id).isEqualTo(404);
            assertThat(response.<String>json("$.title")).isEqualTo("Not Found");
        }
    }

    @Test
    void theDatabaseRefusesALinkToAnotherWorkspacesClient() {
        String aliceClient = alice.post(aliceClients, "{\"name\":\"Anna\"}").json("$.id");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO photo_sessions (workspace_id, client_id, title, status)
                VALUES (?::uuid, ?::uuid, 'Planted', 'INQUIRY')""", bobWorkspace, aliceClient))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("photo_sessions_client_fk");
    }

    private Response status(String sessionId, String status) {
        return alice.post(aliceSessions + "/" + sessionId + "/status", "{\"status\":\"%s\"}".formatted(status));
    }

    private static String session(String title, String startLocal) {
        return "{\"title\":\"%s\",\"startLocal\":\"%s\"}".formatted(title, startLocal);
    }

    private static String withClient(String clientId) {
        return "{\"title\":\"Shoot\",\"clientId\":\"%s\"}".formatted(clientId);
    }

    private static String update(int version, String session) {
        return "{\"version\":%d,\"session\":%s}".formatted(version, session);
    }
}
