package com.photospace.dashboard;

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

/**
 * Dashboard layouts: role defaults, saving with optimistic locking, reset, validation, one layout per member, and
 * that no user can read or change a layout through another workspace.
 */
@IntegrationTest
class DashboardIT {

    private static final List<String> OWNER_DEFAULT = List.of("AT_A_GLANCE", "UPCOMING_SESSIONS", "MY_TASKS",
            "OPEN_INQUIRIES", "CONTENT_TO_REVIEW", "GALLERIES", "RECENT_ACTIVITY", "QUICK_ACTIONS");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String layout;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        layout = "/api/workspaces/" + aliceWorkspace + "/dashboard/layout";
    }

    @Test
    void anUncustomizedDashboardShowsTheDefaultForTheRole() {
        Response owner = alice.get(layout);
        assertThat(owner.status()).isEqualTo(200);
        assertThat(owner.<List<String>>json("$.widgets[*].type")).containsExactlyElementsOf(OWNER_DEFAULT);
        assertThat(owner.<String>json("$.widgets[0].width")).isEqualTo("FULL");
        assertThat(owner.<String>json("$.widgets[1].width")).isEqualTo("HALF");
        assertThat(owner.<Boolean>json("$.customized")).isFalse();
        assertThat(owner.<Integer>json("$.version")).isZero();
        assertThat(owner.<Object>json("$.updatedAt")).isNull();

        addBob("MEMBER");
        assertThat(bob.get(layout).<List<String>>json("$.widgets[*].type"))
                .as("the audit log is for owners")
                .doesNotContain("RECENT_ACTIVITY")
                .hasSize(OWNER_DEFAULT.size() - 1);
        assertThat(count("SELECT count(*) FROM dashboard_layouts WHERE workspace_id = ?::uuid"))
                .as("reading stores nothing")
                .isZero();
    }

    @Test
    void savesReplacesAndResetsTheLayout() {
        Response saved = alice.put(layout, """
                {"version":0,"widgets":[{"type":"MY_TASKS","width":"FULL"},{"type":"QUICK_ACTIONS","width":"HALF"},
                 {"type":"UPCOMING_SESSIONS","width":"HALF"}]}""");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.<List<String>>json("$.widgets[*].type"))
                .containsExactly("MY_TASKS", "QUICK_ACTIONS", "UPCOMING_SESSIONS");
        assertThat(saved.<List<String>>json("$.widgets[*].width")).containsExactly("FULL", "HALF", "HALF");
        assertThat(saved.<Boolean>json("$.customized")).isTrue();
        assertThat(saved.<Integer>json("$.version")).isEqualTo(1);
        assertThat(saved.<String>json("$.updatedAt")).isNotNull();
        assertThat(alice.get(layout).<List<String>>json("$.widgets[*].type"))
                .containsExactly("MY_TASKS", "QUICK_ACTIONS", "UPCOMING_SESSIONS");

        Response stale = alice.put(layout, "{\"version\":0,\"widgets\":[]}");
        assertThat(stale.status()).isEqualTo(409);

        Response empty = alice.put(layout, "{\"version\":1,\"widgets\":[]}");
        assertThat(empty.<List<String>>json("$.widgets")).as("an empty dashboard is a choice, not the default").isEmpty();
        assertThat(empty.<Boolean>json("$.customized")).isTrue();
        assertThat(empty.<Integer>json("$.version")).isEqualTo(2);

        Response reset = alice.delete(layout);
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.<List<String>>json("$.widgets[*].type")).containsExactlyElementsOf(OWNER_DEFAULT);
        assertThat(reset.<Boolean>json("$.customized")).isFalse();
        assertThat(alice.delete(layout).status()).as("idempotent").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM dashboard_widgets WHERE workspace_id = ?::uuid")).isZero();

        assertThat(alice.put(layout, "{\"version\":2,\"widgets\":[]}").status())
                .as("a window from before the reset is stale")
                .isEqualTo(409);
    }

    @Test
    void validatesTheLayout() {
        Response duplicate = alice.put(layout, """
                {"version":0,"widgets":[{"type":"MY_TASKS","width":"HALF"},{"type":"MY_TASKS","width":"FULL"}]}""");
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.<String>json("$.errors.widgets")).isEqualTo("lists a widget more than once");

        assertThat(alice.put(layout, "{\"version\":0,\"widgets\":[{\"type\":\"PAYMENTS\",\"width\":\"HALF\"}]}").status())
                .isEqualTo(400);
        assertThat(alice.put(layout, "{\"version\":0,\"widgets\":[{\"type\":\"MY_TASKS\",\"width\":\"WIDE\"}]}").status())
                .isEqualTo(400);
        assertThat(alice.put(layout, "{\"version\":0,\"widgets\":[{\"type\":\"MY_TASKS\"}]}").status()).isEqualTo(400);
        assertThat(alice.put(layout, "{\"version\":0,\"widgets\":[null]}").status()).isEqualTo(400);
        assertThat(alice.put(layout, "{\"widgets\":[]}").<String>json("$.errors.version")).isNotBlank();
        assertThat(alice.put(layout, "{\"version\":0}").<String>json("$.errors.widgets")).isNotBlank();
        assertThat(count("SELECT count(*) FROM dashboard_layouts WHERE workspace_id = ?::uuid")).isZero();
    }

    @Test
    void eachMemberHasTheirOwnLayout() {
        addBob("MEMBER");
        assertThat(bob.put(layout, "{\"version\":0,\"widgets\":[{\"type\":\"GALLERIES\",\"width\":\"FULL\"}]}").status())
                .isEqualTo(200);

        assertThat(bob.get(layout).<List<String>>json("$.widgets[*].type")).containsExactly("GALLERIES");
        assertThat(alice.get(layout).<List<String>>json("$.widgets[*].type")).containsExactlyElementsOf(OWNER_DEFAULT);
        assertThat(alice.get(layout).<Boolean>json("$.customized")).isFalse();

        alice.delete(layout);
        assertThat(bob.get(layout).<List<String>>json("$.widgets[*].type"))
                .as("resetting one member's layout leaves the others")
                .containsExactly("GALLERIES");
    }

    @Test
    void ownerOnlyWidgetsAreRefusedToAndHiddenFromMembers() {
        addBob("OWNER");
        assertThat(bob.put(layout, """
                {"version":0,"widgets":[{"type":"RECENT_ACTIVITY","width":"HALF"},{"type":"MY_TASKS","width":"HALF"}]}""")
                .status()).isEqualTo(200);

        jdbc.update("UPDATE workspace_members SET role = 'MEMBER' WHERE workspace_id = ?::uuid AND user_id = ?::uuid",
                aliceWorkspace, bob.get("/api/me").json("$.id"));
        assertThat(bob.get(layout).<List<String>>json("$.widgets[*].type"))
                .as("saved while an owner, hidden once a member")
                .containsExactly("MY_TASKS");

        Response refused = bob.put(layout, "{\"version\":1,\"widgets\":[{\"type\":\"RECENT_ACTIVITY\",\"width\":\"HALF\"}]}");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.<String>json("$.errors.widgets")).isEqualTo("includes a widget only owners can use");
    }

    @Test
    void otherWorkspacesCannotReadOrChangeTheLayout() {
        alice.put(layout, "{\"version\":0,\"widgets\":[{\"type\":\"MY_TASKS\",\"width\":\"HALF\"}]}");
        String bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");

        // Through Alice's workspace, where the membership check itself refuses.
        assertThat(bob.get(layout).status()).isEqualTo(404);
        assertThat(bob.put(layout, "{\"version\":1,\"widgets\":[]}").status()).isEqualTo(404);
        assertThat(bob.delete(layout).status()).isEqualTo(404);

        // Bob's own workspace has his own, default layout.
        Response own = bob.get("/api/workspaces/" + bobWorkspace + "/dashboard/layout");
        assertThat(own.<Boolean>json("$.customized")).isFalse();

        Response unchanged = alice.get(layout);
        assertThat(unchanged.<List<String>>json("$.widgets[*].type")).containsExactly("MY_TASKS");
        assertThat(unchanged.<Integer>json("$.version")).isEqualTo(1);
    }

    private void addBob(String role) {
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)",
                aliceWorkspace, bob.get("/api/me").json("$.id"), role);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, aliceWorkspace);
    }
}
