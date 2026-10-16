package com.photospace.clients;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;

/** Client CRUD, validation, archiving, paging, and that no user can reach another workspace's clients. */
@IntegrationTest
class ClientsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceClients;
    private String bobWorkspace;
    private String bobClients;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceClients = "/api/workspaces/" + alice.createWorkspace("Alice Studio", "Europe/Warsaw") + "/clients";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
        bobClients = "/api/workspaces/" + bobWorkspace + "/clients";
    }

    @Test
    void createsReadsAndUpdatesAClient() {
        Response created = alice.post(aliceClients, """
                {"name":"  Anna Nowak ","email":" anna@example.com","phone":"+48 600 100 200","notes":"   "}""");

        assertThat(created.status()).isEqualTo(201);
        String id = created.json("$.id");
        assertThat(created.<String>json("$.name")).isEqualTo("Anna Nowak");
        assertThat(created.<String>json("$.email")).isEqualTo("anna@example.com");
        assertThat(created.<String>json("$.phone")).isEqualTo("+48 600 100 200");
        assertThat(created.<Object>json("$.notes")).isNull();
        assertThat(created.<Object>json("$.archivedAt")).isNull();
        assertThat(created.<Integer>json("$.version")).isZero();

        assertThat(alice.get(aliceClients + "/" + id).<String>json("$.name")).isEqualTo("Anna Nowak");

        Response updated = alice.put(aliceClients + "/" + id, """
                {"version":0,"client":{"name":"Anna Kowalska","email":"","phone":null,"notes":"Prefers mornings"}}""");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.<String>json("$.name")).isEqualTo("Anna Kowalska");
        assertThat(updated.<Object>json("$.email")).isNull();
        assertThat(updated.<Object>json("$.phone")).isNull();
        assertThat(updated.<String>json("$.notes")).isEqualTo("Prefers mornings");
        assertThat(updated.<Integer>json("$.version")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidFields() {
        Response invalid = alice.post(aliceClients, """
                {"name":"  ","email":"not-an-email","phone":"call me","notes":"%s"}""".formatted("x".repeat(10_001)));

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.<Map<String, String>>json("$.errors"))
                .containsOnlyKeys("name", "email", "phone", "notes");
        assertThat(invalid.<String>json("$.correlationId")).isNotBlank();

        assertThat(alice.post(aliceClients, client("x".repeat(201))).status()).isEqualTo(400);

        String id = alice.post(aliceClients, client("Anna")).json("$.id");
        Response noVersion = alice.put(aliceClients + "/" + id, "{\"client\":" + client("Anna") + "}");
        assertThat(noVersion.status()).isEqualTo(400);
        assertThat(noVersion.<String>json("$.errors.version")).isNotBlank();
        Response noClient = alice.put(aliceClients + "/" + id, "{\"version\":0}");
        assertThat(noClient.<String>json("$.errors.client")).isNotBlank();
    }

    @Test
    void ignoresClientSuppliedIdsAndWorkspace() {
        String aliceWorkspace = aliceClients.split("/")[3];
        UUID chosenId = UUID.randomUUID();
        Response created = bob.post(bobClients, """
                {"name":"Sneaky","id":"%s","workspaceId":"%s","version":7,"archivedAt":"2020-01-01T00:00:00Z"}"""
                .formatted(chosenId, aliceWorkspace));

        assertThat(created.status()).isEqualTo(201);
        String id = created.json("$.id");
        assertThat(id).isNotEqualTo(chosenId.toString());
        assertThat(created.<Integer>json("$.version")).isZero();
        assertThat(created.<Object>json("$.archivedAt")).isNull();
        assertThat(jdbc.queryForObject("SELECT workspace_id::text FROM clients WHERE id = ?::uuid", String.class, id))
                .isEqualTo(bobWorkspace);
    }

    @Test
    void rejectsAStaleVersion() {
        String id = alice.post(aliceClients, client("Anna")).json("$.id");
        assertThat(alice.put(aliceClients + "/" + id, update(0, "First edit")).status()).isEqualTo(200);

        Response stale = alice.put(aliceClients + "/" + id, update(0, "Second edit from an old page"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.<String>json("$.detail")).contains("changed by someone else");
        assertThat(alice.get(aliceClients + "/" + id).<String>json("$.name")).isEqualTo("First edit");
    }

    @Test
    void archivesAndRestores() {
        String id = alice.post(aliceClients, client("Anna")).json("$.id");

        Response archived = alice.post(aliceClients + "/" + id + "/archive");
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.<String>json("$.archivedAt")).isNotBlank();
        assertThat(alice.post(aliceClients + "/" + id + "/archive").<String>json("$.archivedAt"))
                .as("archiving twice keeps the first time")
                .isEqualTo(archived.<String>json("$.archivedAt"));

        assertThat(alice.get(aliceClients).<List<String>>json("$.items[*].id")).doesNotContain(id);
        assertThat(alice.get(aliceClients + "?archived=true").<List<String>>json("$.items[*].id")).containsExactly(id);
        assertThat(alice.get(aliceClients + "/" + id).status()).as("still readable").isEqualTo(200);

        int version = archived.json("$.version");
        Response edit = alice.put(aliceClients + "/" + id, update(version, "Edited while archived"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.<String>json("$.detail")).contains("archived");

        Response restored = alice.post(aliceClients + "/" + id + "/restore");
        assertThat(restored.<Object>json("$.archivedAt")).isNull();
        assertThat(alice.put(aliceClients + "/" + id, update(restored.json("$.version"), "Back")).status())
                .isEqualTo(200);
        assertThat(alice.get(aliceClients).<List<String>>json("$.items[*].id")).containsExactly(id);
    }

    @Test
    void pagesThroughClientsSortedByNameIgnoringCase() {
        for (String name : List.of("delta", "Bravo", "echo", "Alpha", "charlie")) {
            alice.post(aliceClients, client(name));
        }

        Response first = alice.get(aliceClients + "?size=2");
        assertThat(first.<List<String>>json("$.items[*].name")).containsExactly("Alpha", "Bravo");
        assertThat(first.<Integer>json("$.totalItems")).isEqualTo(5);
        assertThat(first.<Integer>json("$.page")).isZero();
        assertThat(first.<Integer>json("$.size")).isEqualTo(2);
        assertThat(alice.get(aliceClients + "?size=2&page=2").<List<String>>json("$.items[*].name"))
                .containsExactly("echo");
        assertThat(alice.get(aliceClients + "?size=2&page=9").<List<String>>json("$.items")).isEmpty();
        assertThat(alice.get(aliceClients).<Integer>json("$.size")).isEqualTo(25);
    }

    @Test
    void searchesByNameOrEmail() {
        alice.post(aliceClients, """
                {"name":"Anna Nowak","email":"anna@studio.example"}""");
        alice.post(aliceClients, """
                {"name":"Piotr Zieliński","email":"pz@example.com"}""");
        alice.post(aliceClients, """
                {"name":"100% Weddings"}""");

        assertThat(alice.get(aliceClients + "?q=NOWAK").<List<String>>json("$.items[*].name"))
                .containsExactly("Anna Nowak");
        assertThat(alice.get(aliceClients + "?q=studio.example").<List<String>>json("$.items[*].name"))
                .containsExactly("Anna Nowak");
        assertThat(alice.get(aliceClients + "?q=%25").<List<String>>json("$.items[*].name"))
                .as("%% is matched literally, not as a wildcard")
                .containsExactly("100% Weddings");
        assertThat(alice.get(aliceClients + "?q=%20%20").<Integer>json("$.totalItems")).as("blank = no filter")
                .isEqualTo(3);
    }

    @Test
    void rejectsInvalidPaging() {
        Response tooBig = alice.get(aliceClients + "?size=101");
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.<String>json("$.errors.size")).isNotBlank();

        assertThat(alice.get(aliceClients + "?page=-1").<String>json("$.errors.page")).isNotBlank();
        assertThat(alice.get(aliceClients + "?size=0").status()).isEqualTo(400);
        assertThat(alice.get(aliceClients + "?page=abc").<String>json("$.errors.page")).isNotBlank();
        assertThat(alice.get(aliceClients + "?q=" + "x".repeat(101)).<String>json("$.errors.q")).isNotBlank();
    }

    @Test
    void otherWorkspacesCannotReadListOrChangeTheClient() {
        String id = alice.post(aliceClients, client("Anna")).json("$.id");

        // Through Bob's own workspace, where the membership check passes but the client is not his.
        assertThat(bob.get(bobClients + "/" + id).status()).isEqualTo(404);
        assertThat(bob.put(bobClients + "/" + id, update(0, "Hijacked")).status()).isEqualTo(404);
        assertThat(bob.post(bobClients + "/" + id + "/archive").status()).isEqualTo(404);
        assertThat(bob.post(bobClients + "/" + id + "/restore").status()).isEqualTo(404);
        assertThat(bob.get(bobClients).<List<String>>json("$.items[*].id")).doesNotContain(id);
        assertThat(bob.get(bobClients + "?q=Anna").<Integer>json("$.totalItems")).isZero();

        // Through Alice's workspace, where the membership check itself refuses.
        assertThat(bob.get(aliceClients).status()).isEqualTo(404);
        assertThat(bob.get(aliceClients + "/" + id).status()).isEqualTo(404);
        assertThat(bob.post(aliceClients, client("Planted")).status()).isEqualTo(404);
        assertThat(bob.put(aliceClients + "/" + id, update(0, "Hijacked")).status()).isEqualTo(404);

        Response unchanged = alice.get(aliceClients + "/" + id);
        assertThat(unchanged.<String>json("$.name")).isEqualTo("Anna");
        assertThat(unchanged.<Object>json("$.archivedAt")).isNull();
        assertThat(alice.get(aliceClients).<Integer>json("$.totalItems")).isEqualTo(1);
    }

    @Test
    void unknownForeignAndMalformedClientIdsAreIndistinguishable() {
        String foreign = bob.post(bobClients, client("Bob's client")).json("$.id");

        for (String id : List.of(foreign, UUID.randomUUID().toString(), "not-a-uuid")) {
            Response response = alice.get(aliceClients + "/" + id);
            assertThat(response.status()).as(id).isEqualTo(404);
            assertThat(response.<String>json("$.title")).isEqualTo("Not Found");
        }
    }

    @Test
    void membersCanManageClients() {
        String aliceWorkspace = aliceClients.split("/")[3];
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, bob.get("/api/me").<String>json("$.id"));

        Response created = bob.post(aliceClients, client("Added by assistant"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(bob.post(aliceClients + "/" + created.json("$.id") + "/archive").status()).isEqualTo(200);
    }

    private static String client(String name) {
        return "{\"name\":\"%s\"}".formatted(name);
    }

    private static String update(int version, String name) {
        return "{\"version\":%d,\"client\":%s}".formatted(version, client(name));
    }
}
