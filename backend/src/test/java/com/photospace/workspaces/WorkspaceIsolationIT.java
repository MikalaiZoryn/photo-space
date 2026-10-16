package com.photospace.workspaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Workspace creation and selection, and that no user can reach another workspace by changing an ID. */
@IntegrationTest
class WorkspaceIsolationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        Response created = alice.post("/api/workspaces", workspace("Alice Studio", "Europe/Warsaw"));
        assertThat(created.status()).isEqualTo(201);
        aliceWorkspace = created.json("$.id");
    }

    @Test
    void creatorOwnsTheNewWorkspaceAndCanReadIt() {
        Response workspace = alice.get("/api/workspaces/" + aliceWorkspace);
        assertThat(workspace.status()).isEqualTo(200);
        assertThat(workspace.<String>json("$.name")).isEqualTo("Alice Studio");
        assertThat(workspace.<String>json("$.timezone")).isEqualTo("Europe/Warsaw");
        assertThat(workspace.<String>json("$.role")).isEqualTo("OWNER");

        assertThat(alice.get("/api/workspaces").<List<String>>json("$[*].id")).containsExactly(aliceWorkspace);
        Response members = alice.get("/api/workspaces/" + aliceWorkspace + "/members");
        assertThat(members.<List<String>>json("$[*].role")).containsExactly("OWNER");
    }

    @Test
    void ownerCanUpdateSettings() {
        Response updated = alice.put("/api/workspaces/" + aliceWorkspace, workspace("Renamed", "America/New_York"));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.<String>json("$.name")).isEqualTo("Renamed");
        assertThat(updated.<String>json("$.timezone")).isEqualTo("America/New_York");
    }

    @Test
    void clientSuppliedIdsAndRolesAreIgnoredOnCreate() {
        Response created = bob.post("/api/workspaces", """
                {"name":"Bob Studio","timezone":"UTC","id":"%s","role":"MEMBER","workspaceId":"%s"}
                """.formatted(aliceWorkspace, aliceWorkspace));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.id")).isNotEqualTo(aliceWorkspace);
        assertThat(created.<String>json("$.role")).isEqualTo("OWNER");
        assertThat(bob.get("/api/workspaces/" + aliceWorkspace).status()).isEqualTo(404);
    }

    @Test
    void invalidSettingsAreRejected() {
        Response blank = alice.post("/api/workspaces", workspace("  ", "UTC"));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.<String>json("$.errors.name")).isNotBlank();

        Response badZone = alice.post("/api/workspaces", workspace("Studio", "Mars/Olympus_Mons"));
        assertThat(badZone.status()).isEqualTo(400);
        assertThat(badZone.<String>json("$.errors.timezone")).isNotBlank();

        assertThat(alice.post("/api/workspaces", workspace("x".repeat(101), "UTC")).status()).isEqualTo(400);
        assertThat(alice.put("/api/workspaces/" + aliceWorkspace, workspace("", "UTC")).status()).isEqualTo(400);
    }

    @Test
    void otherUsersCannotSeeOrListTheWorkspace() {
        assertThat(bob.get("/api/workspaces/" + aliceWorkspace).status()).isEqualTo(404);
        assertThat(bob.get("/api/workspaces/" + aliceWorkspace + "/members").status()).isEqualTo(404);
        assertThat(bob.get("/api/workspaces").<List<String>>json("$[*].id")).doesNotContain(aliceWorkspace);
    }

    @Test
    void otherUsersCannotModifyTheWorkspace() {
        Response response = bob.put("/api/workspaces/" + aliceWorkspace, workspace("Hijacked", "UTC"));

        assertThat(response.status()).isEqualTo(404);
        assertThat(alice.get("/api/workspaces/" + aliceWorkspace).<String>json("$.name")).isEqualTo("Alice Studio");
    }

    @Test
    void foreignUnknownAndMalformedIdsAreIndistinguishable() {
        Response foreign = bob.get("/api/workspaces/" + aliceWorkspace);
        Response unknown = bob.get("/api/workspaces/" + UUID.randomUUID());
        Response malformed = bob.get("/api/workspaces/not-a-uuid/members");

        for (Response response : List.of(foreign, unknown, malformed)) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.<String>json("$.title")).isEqualTo("Not Found");
        }
    }

    @Test
    void membersCanReadButOnlyOwnersCanChangeSettings() {
        String bobId = bob.get("/api/me").json("$.id");
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, bobId);

        Response read = bob.get("/api/workspaces/" + aliceWorkspace);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.<String>json("$.role")).isEqualTo("MEMBER");
        assertThat(bob.get("/api/workspaces/" + aliceWorkspace + "/members").<List<String>>json("$[*].role"))
                .containsExactly("OWNER", "MEMBER");

        assertThat(bob.put("/api/workspaces/" + aliceWorkspace, workspace("Renamed by member", "UTC")).status())
                .isEqualTo(403);
        assertThat(alice.get("/api/workspaces/" + aliceWorkspace).<String>json("$.name")).isEqualTo("Alice Studio");
    }

    private static String workspace(String name, String timezone) {
        return "{\"name\":\"%s\",\"timezone\":\"%s\"}".formatted(name, timezone);
    }
}
