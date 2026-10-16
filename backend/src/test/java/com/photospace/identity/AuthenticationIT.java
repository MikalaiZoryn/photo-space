package com.photospace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.IntegrationTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Sign-in, sessions, CSRF, and sign-out through real HTTP, as the browser sees them. */
@IntegrationTest
class AuthenticationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void protectedEndpointsRejectUnauthenticatedRequestsWithoutCreatingSessions() {
        ApiClient anonymous = new ApiClient(port);
        String someWorkspace = "/api/workspaces/" + UUID.randomUUID();

        for (String path : List.of("/api/me", "/api/workspaces", someWorkspace, someWorkspace + "/members")) {
            Response response = anonymous.get(path);

            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.<String>json("$.correlationId")).as(path).isNotBlank();
            assertThat(response.setCookies()).as(path).noneMatch(c -> c.startsWith("SESSION="));
        }
    }

    @Test
    void unauthenticatedMutationsAreRejectedEvenWithCsrfToken() {
        Response response = new ApiClient(port).post("/api/workspaces", "{\"name\":\"X\",\"timezone\":\"UTC\"}");

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void publicEndpointsDescribeHealthAndSignInOptions() {
        ApiClient anonymous = new ApiClient(port);

        assertThat(anonymous.get("/api/health").status()).isEqualTo(200);
        Response options = anonymous.get("/api/auth/options");
        assertThat(options.status()).isEqualTo(200);
        assertThat(options.<String>json("$.googleSignInUrl")).isEqualTo("/api/auth/oauth2/authorization/google");
        assertThat(options.<Boolean>json("$.devLogin")).isTrue();
    }

    @Test
    void signInCreatesSecureServerSideSession() {
        String email = "session-" + UUID.randomUUID() + "@example.com";
        ApiClient client = new ApiClient(port);

        client.signIn(email);

        assertThat(client.cookie("SESSION")).isNotBlank();
        Response me = client.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.<String>json("$.email")).isEqualTo(email);
        String userId = me.json("$.id");
        Integer sessions = jdbc.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, userId);
        assertThat(sessions).isEqualTo(1);
    }

    @Test
    void sessionCookieIsHttpOnlySecureAndSameSiteLax() {
        Response response = new ApiClient(port)
                .postWithoutCsrf("/api/dev/login", "{\"email\":\"cookie-" + UUID.randomUUID() + "@example.com\"}");

        assertThat(response.status()).isEqualTo(204);
        String sessionCookie = response.setCookies().stream()
                .filter(c -> c.startsWith("SESSION="))
                .findFirst()
                .orElseThrow();

        assertThat(sessionCookie).contains("HttpOnly").contains("Secure").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void signInRotatesTheSessionId() {
        ApiClient client = new ApiClient(port);
        client.signIn("rotate-" + UUID.randomUUID() + "@example.com");
        String before = client.cookie("SESSION");

        client.signIn("rotate-" + UUID.randomUUID() + "@example.com");

        assertThat(client.cookie("SESSION")).isNotEqualTo(before);
    }

    @Test
    void stateChangingRequestsRequireTheCsrfHeader() {
        ApiClient client = new ApiClient(port).signIn("csrf-" + UUID.randomUUID() + "@example.com");
        String body = "{\"name\":\"Studio\",\"timezone\":\"UTC\"}";

        assertThat(client.postWithoutCsrf("/api/workspaces", body).status()).isEqualTo(403);
        assertThat(client.post("/api/workspaces", body).status()).isEqualTo(201);
    }

    @Test
    void signOutEndsTheServerSideSession() {
        ApiClient client = new ApiClient(port).signIn("logout-" + UUID.randomUUID() + "@example.com");
        String oldSession = client.cookie("SESSION");

        assertThat(client.post("/api/auth/logout", null).status()).isEqualTo(204);

        ApiClient replay = new ApiClient(port);
        replay.setCookie("SESSION", oldSession);
        assertThat(replay.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void googleSignInRequestsOnlyOpenidAndEmailWithStateAndNonce() {
        Response response = new ApiClient(port).get("/api/auth/oauth2/authorization/google");

        assertThat(response.status()).isEqualTo(302);
        URI location = URI.create(response.header("Location"));
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        Map<String, String> query = queryParameters(location);
        assertThat(query.get("scope")).isEqualTo("openid email");
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("client_id")).isEqualTo("test-client-id");
        assertThat(query.get("redirect_uri"))
                .isEqualTo("http://localhost:" + port + "/api/auth/oauth2/callback/google");
        assertThat(query.get("state")).isNotBlank();
        assertThat(query.get("nonce")).isNotBlank();
        assertThat(location.toString()).doesNotContainIgnoringCase("gmail").doesNotContainIgnoringCase("calendar");
    }

    @Test
    void callbackWithUnknownStateDoesNotSignIn() {
        ApiClient client = new ApiClient(port);
        client.get("/api/auth/oauth2/authorization/google"); // Starts a real authorization request.

        Response callback = client.get("/api/auth/oauth2/callback/google?code=attacker-code&state=forged-state");

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.header("Location")).endsWith("/?signInError");
        assertThat(client.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void responsesCarryCorrelationId() {
        Response response = new ApiClient(port).get("/api/me");

        assertThat(response.header("X-Correlation-Id")).isNotBlank();
        assertThat(response.<String>json("$.correlationId")).isEqualTo(response.header("X-Correlation-Id"));
    }

    private static Map<String, String> queryParameters(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> pair[0],
                        pair -> URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8)));
    }
}
