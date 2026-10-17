package com.photospace.invitations;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.mail.EmailMessage;
import com.photospace.mail.TestEmailSender;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inviting people by email: owners invite, resend, and revoke; the invited person accepts with the link from the
 * email, signed in with the invited address. Other workspaces and plain members cannot touch invitations.
 */
@IntegrationTest
class InvitationsIT {

    private static final Pattern LINK = Pattern.compile("http://localhost:5173/#/invite/([A-Za-z0-9_-]{43})");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private String workspace;
    private String base;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn(uniqueEmail("alice"));
        workspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        base = "/api/workspaces/" + workspace + "/invitations";
    }

    @Test
    void invitedPersonJoinsWithTheLinkFromTheEmail() {
        String email = uniqueEmail("carol");
        Response created = alice.post(base, invite(email.toUpperCase()));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.email")).isEqualTo(email);
        assertThat(created.<String>json("$.role")).isEqualTo("MEMBER");
        assertThat(created.<String>json("$.state")).isEqualTo("PENDING");
        String id = created.json("$.id");

        String token = awaitToken(email, 1);
        EmailMessage message = TestEmailSender.sentTo(email).getFirst();
        assertThat(message.subject()).isEqualTo("You're invited to join Alice Studio on Photo Space");
        assertThat(message.textBody()).contains("Alice Studio", email, "(Europe/Warsaw)");
        assertThat(Eventually.await(() -> pending(alice).getFirst().get("emailStatus"), "SENT"::equals)).isEqualTo("SENT");
        // Only the hash is stored, and no response repeats the token.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_invitations i WHERE i::text LIKE ?",
                Long.class, "%" + token + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT token_hash = sha256(convert_to(?, 'UTF8')) FROM workspace_invitations WHERE id = ?::uuid",
                Boolean.class, token, id)).isTrue();
        assertThat(alice.get(base).body()).doesNotContain(token);

        ApiClient carol = new ApiClient(port).signIn(email);
        Response preview = carol.getWithHeaders("/api/invitation", tokenHeader(token));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.<String>json("$.workspaceName")).isEqualTo("Alice Studio");
        assertThat(preview.<String>json("$.email")).isEqualTo(email);
        assertThat(preview.<String>json("$.state")).isEqualTo("PENDING");
        assertThat(preview.<Boolean>json("$.emailMatches")).isTrue();
        assertThat(preview.<Boolean>json("$.alreadyMember")).isFalse();

        Response accepted = carol.postWithHeaders("/api/invitation/accept", tokenHeader(token));
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.<String>json("$.id")).isEqualTo(workspace);
        assertThat(accepted.<String>json("$.role")).isEqualTo("MEMBER");
        assertThat(carol.get("/api/workspaces").<List<String>>json("$[*].id")).containsExactly(workspace);
        assertThat(alice.get("/api/workspaces/" + workspace + "/members").<List<String>>json("$[*].role"))
                .containsExactly("OWNER", "MEMBER");
        assertThat(pending(alice)).isEmpty();

        // Accepting again as the same person changes nothing; the used link works for nobody else.
        assertThat(carol.postWithHeaders("/api/invitation/accept", tokenHeader(token)).status()).isEqualTo(200);
        assertThat(alice.get("/api/workspaces/" + workspace + "/members").<List<String>>json("$[*].role")).hasSize(2);
        ApiClient mallory = new ApiClient(port).signIn(uniqueEmail("mallory"));
        assertThat(mallory.postWithHeaders("/api/invitation/accept", tokenHeader(token)).status()).isEqualTo(409);

        assertThat(jdbc.queryForList("""
                SELECT action FROM audit_events WHERE workspace_id = ?::uuid AND target_id = ?::uuid ORDER BY created_at, id""",
                String.class, workspace, id)).containsExactly("INVITATION_CREATED", "INVITATION_ACCEPTED");
    }

    @Test
    void onlyTheInvitedAddressCanAccept() {
        String email = uniqueEmail("dave");
        alice.post(base, invite(email));
        String token = awaitToken(email, 1);

        ApiClient eve = new ApiClient(port).signIn(uniqueEmail("eve"));
        Response preview = eve.getWithHeaders("/api/invitation", tokenHeader(token));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.<Boolean>json("$.emailMatches")).isFalse();
        Response refused = eve.postWithHeaders("/api/invitation/accept", tokenHeader(token));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.<String>json("$.detail")).contains("different email address");
        assertThat(eve.get("/api/workspaces/" + workspace).status()).isEqualTo(404);

        // An unverified identity with the right address is not enough.
        ApiClient dave = new ApiClient(port).signIn(email);
        jdbc.update("UPDATE external_identities SET email_verified = false WHERE provider = 'dev' AND subject = ?", email);
        assertThat(dave.postWithHeaders("/api/invitation/accept", tokenHeader(token)).status()).isEqualTo(409);
        assertThat(dave.get("/api/workspaces/" + workspace).status()).isEqualTo(404);
    }

    @Test
    void revokedAndExpiredInvitationsCannotBeAccepted() {
        String revokedEmail = uniqueEmail("frank");
        String revokedId = alice.post(base, invite(revokedEmail)).json("$.id");
        String revokedToken = awaitToken(revokedEmail, 1);
        Response revoked = alice.post(base + "/" + revokedId + "/revoke");
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.<String>json("$.state")).isEqualTo("REVOKED");
        assertThat(alice.post(base + "/" + revokedId + "/revoke").status()).isEqualTo(200); // idempotent
        ApiClient frank = new ApiClient(port).signIn(revokedEmail);
        assertThat(frank.getWithHeaders("/api/invitation", tokenHeader(revokedToken)).<String>json("$.state"))
                .isEqualTo("REVOKED");
        assertThat(frank.postWithHeaders("/api/invitation/accept", tokenHeader(revokedToken)).status()).isEqualTo(409);
        assertThat(alice.post(base + "/" + revokedId + "/resend").status()).isEqualTo(409);

        String expiredEmail = uniqueEmail("grace");
        String expiredId = alice.post(base, invite(expiredEmail)).json("$.id");
        String expiredToken = awaitToken(expiredEmail, 1);
        jdbc.update("UPDATE workspace_invitations SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", expiredId);
        ApiClient grace = new ApiClient(port).signIn(expiredEmail);
        assertThat(grace.getWithHeaders("/api/invitation", tokenHeader(expiredToken)).<String>json("$.state"))
                .isEqualTo("EXPIRED");
        assertThat(grace.postWithHeaders("/api/invitation/accept", tokenHeader(expiredToken)).status()).isEqualTo(409);
        assertThat(pending(alice)).extracting(i -> i.get("state")).containsExactly("EXPIRED");
        assertThat(grace.get("/api/workspaces/" + workspace).status()).isEqualTo(404);
    }

    @Test
    void resendingSendsANewLinkAndRetiresTheOldOne() {
        String email = uniqueEmail("heidi");
        String id = alice.post(base, invite(email)).json("$.id");
        String firstToken = awaitToken(email, 1);

        Response tooSoon = alice.post(base + "/" + id + "/resend");
        assertThat(tooSoon.status()).isEqualTo(409);

        // An expired invitation can be resent, which also opens it again.
        jdbc.update("""
                UPDATE workspace_invitations SET email_requested_at = now() - interval '2 minutes',
                       expires_at = now() - interval '1 second' WHERE id = ?::uuid""", id);
        Response resent = alice.post(base + "/" + id + "/resend");
        assertThat(resent.status()).isEqualTo(200);
        assertThat(resent.<String>json("$.state")).isEqualTo("PENDING");
        String secondToken = awaitToken(email, 2);
        assertThat(secondToken).isNotEqualTo(firstToken);

        ApiClient heidi = new ApiClient(port).signIn(email);
        assertThat(heidi.getWithHeaders("/api/invitation", tokenHeader(firstToken)).status()).isEqualTo(404);
        assertThat(heidi.postWithHeaders("/api/invitation/accept", tokenHeader(secondToken)).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_events WHERE action = 'INVITATION_RESENT' AND target_id = ?::uuid",
                String.class, id)).isEqualTo("send 2");
    }

    @Test
    void failedEmailsAreShownToTheOwner() {
        // A permanent failure gives up at once.
        String ivan = uniqueEmail("ivan");
        TestEmailSender.failNext(ivan, true);
        String permanentId = alice.post(base, invite(ivan)).json("$.id");
        awaitEmailStatus(permanentId, "FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM background_jobs WHERE subject_id = ?::uuid", Integer.class,
                permanentId)).isEqualTo(1);

        // Temporary failures are retried until the attempts run out (3 in tests).
        String judy = uniqueEmail("judy");
        TestEmailSender.failNext(judy, false, false, false);
        String temporaryId = alice.post(base, invite(judy)).json("$.id");
        awaitEmailStatus(temporaryId, "FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM background_jobs WHERE subject_id = ?::uuid", String.class,
                temporaryId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT token_hash IS NULL FROM workspace_invitations WHERE id = ?::uuid",
                Boolean.class, temporaryId)).isTrue();
    }

    @Test
    void invitationsAreValidatedAndLimited() {
        assertThat(alice.post(base, invite("not-an-email")).status()).isEqualTo(400);
        assertThat(alice.post(base, "{\"email\":\"\"}").status()).isEqualTo(400);

        String email = uniqueEmail("ken");
        assertThat(alice.post(base, invite(email)).status()).isEqualTo(201);
        Response duplicate = alice.post(base, invite(email.toUpperCase()));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.<String>json("$.detail")).contains("Resend");

        for (int i = 1; i < 20; i++) {
            assertThat(alice.post(base, invite(uniqueEmail("bulk"))).status()).isEqualTo(201);
        }
        assertThat(alice.post(base, invite(uniqueEmail("one-too-many"))).status()).isEqualTo(409);
    }

    @Test
    void tokensAreCheckedBeforeAnythingIsRevealed() {
        ApiClient someone = new ApiClient(port).signIn(uniqueEmail("someone"));
        assertThat(someone.get("/api/invitation").status()).isEqualTo(404);
        assertThat(someone.getWithHeaders("/api/invitation", tokenHeader("short")).status()).isEqualTo(404);
        assertThat(someone.getWithHeaders("/api/invitation", tokenHeader("A".repeat(43))).status()).isEqualTo(404);
        assertThat(someone.postWithHeaders("/api/invitation/accept", tokenHeader("A".repeat(43))).status()).isEqualTo(404);

        String email = uniqueEmail("leo");
        alice.post(base, invite(email));
        String token = awaitToken(email, 1);
        ApiClient signedOut = new ApiClient(port);
        assertThat(signedOut.getWithHeaders("/api/invitation", tokenHeader(token)).status()).isEqualTo(401);
        assertThat(signedOut.postWithHeaders("/api/invitation/accept", tokenHeader(token)).status()).isIn(401, 403);
    }

    @Test
    void membersAndOtherWorkspacesCannotManageInvitations() {
        String email = uniqueEmail("mike");
        String id = alice.post(base, invite(email)).json("$.id");
        ApiClient mike = new ApiClient(port).signIn(email);
        assertThat(mike.postWithHeaders("/api/invitation/accept", tokenHeader(awaitToken(email, 1))).status())
                .isEqualTo(200);

        // A plain member of the workspace.
        assertThat(mike.get(base).status()).isEqualTo(403);
        assertThat(mike.post(base, invite(uniqueEmail("x"))).status()).isEqualTo(403);
        String otherId = alice.post(base, invite(uniqueEmail("nina"))).json("$.id");
        assertThat(mike.post(base + "/" + otherId + "/resend").status()).isEqualTo(403);
        assertThat(mike.post(base + "/" + otherId + "/revoke").status()).isEqualTo(403);

        // Another workspace's owner, with Alice's IDs in the path or in their own workspace's path.
        ApiClient bob = new ApiClient(port).signIn(uniqueEmail("bob"));
        String bobBase = "/api/workspaces/" + bob.createWorkspace("Bob Studio", "UTC") + "/invitations";
        assertThat(bob.get(base).status()).isEqualTo(404);
        assertThat(bob.post(base, invite(uniqueEmail("y"))).status()).isEqualTo(404);
        assertThat(bob.post(base + "/" + otherId + "/revoke").status()).isEqualTo(404);
        assertThat(bob.post(bobBase + "/" + otherId + "/revoke").status()).isEqualTo(404);
        assertThat(bob.post(bobBase + "/" + otherId + "/resend").status()).isEqualTo(404);
        assertThat(bob.post(bobBase + "/" + id + "/revoke").status()).isEqualTo(404);
        assertThat(bob.get(bobBase).<List<Object>>json("$")).isEmpty();
        assertThat(pending(alice)).extracting(i -> i.get("id")).containsExactly(otherId);
    }

    private String awaitToken(String email, int nth) {
        List<EmailMessage> messages = Eventually.await(() -> TestEmailSender.sentTo(email), m -> m.size() >= nth);
        Matcher link = LINK.matcher(messages.get(nth - 1).textBody());
        assertThat(link.find()).as("invitation link in the email").isTrue();
        return link.group(1);
    }

    private void awaitEmailStatus(String invitationId, String status) {
        Eventually.await(() -> jdbc.queryForObject("SELECT email_status FROM workspace_invitations WHERE id = ?::uuid",
                String.class, invitationId), status::equals);
    }

    private List<Map<String, Object>> pending(ApiClient client) {
        return client.get(base).json("$");
    }

    private static Map<String, String> tokenHeader(String token) {
        return Map.of("X-Invitation-Token", token);
    }

    private static String invite(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private static String uniqueEmail(String name) {
        return name + "-" + UUID.randomUUID() + "@example.com";
    }
}
