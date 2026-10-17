package com.photospace.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import com.photospace.support.TestImages;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Workspace branding: defaults, saving, validation, the logo upload, auditing, and workspace isolation. */
@IntegrationTest
class BrandingIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String branding;
    private String bobWorkspace;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        branding = "/api/workspaces/" + aliceWorkspace + "/branding";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
    }

    @Test
    void startsWithDefaultsAndSavesChanges() {
        Response defaults = alice.get(branding);
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.<Object>json("$.displayName")).isNull();
        assertThat(defaults.<String>json("$.workspaceName")).isEqualTo("Alice Studio");
        assertThat(defaults.<String>json("$.primaryColor")).isEqualTo("#18181b");
        assertThat(defaults.<String>json("$.accentColor")).isEqualTo("#7c3aed");
        assertThat(defaults.<String>json("$.font")).isEqualTo("INTER");
        assertThat(defaults.<Integer>json("$.version")).isZero();

        Response saved = alice.put(branding, """
                {"version":0,"branding":{"displayName":"  Alice Photo ","primaryColor":"#1A2B3C","accentColor":"#ff0066",
                 "font":"SERIF","email":"hello@alice.example","phone":"+48 600 100 200","description":"  ",
                 "customCss":"body{display:none}"}}""");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.<String>json("$.displayName")).isEqualTo("Alice Photo");
        assertThat(saved.<String>json("$.primaryColor")).as("stored in lower case").isEqualTo("#1a2b3c");
        assertThat(saved.<String>json("$.font")).isEqualTo("SERIF");
        assertThat(saved.<Object>json("$.description")).isNull();
        assertThat(saved.body()).doesNotContain("customCss");
        assertThat(saved.<Integer>json("$.version")).isEqualTo(1);

        assertThat(alice.put(branding, body(0, "#000000")).status()).as("stale version").isEqualTo(409);
        assertThat(alice.put(branding, body(1, "#000000")).<Integer>json("$.version")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT detail FROM audit_events WHERE target_id = ?::uuid AND action = 'BRANDING_UPDATED'"
                        + " ORDER BY created_at, id", String.class, aliceWorkspace))
                .containsExactly("version 1, no logo", "version 2, no logo");
    }

    @Test
    void validatesColorsFontsAndContactDetails() {
        Response invalid = alice.put(branding, """
                {"version":0,"branding":{"primaryColor":"red","accentColor":"#12345","font":"COMIC_SANS"}}""");
        assertThat(invalid.status()).isEqualTo(400);

        Response fields = alice.put(branding, """
                {"version":0,"branding":{"primaryColor":"red","accentColor":"#12345g","font":"INTER",
                 "email":"nope","phone":"call me","description":"%s"}}""".formatted("d".repeat(501)));
        assertThat(fields.status()).isEqualTo(400);
        assertThat(fields.<String>json("$.errors['branding.primaryColor']")).isEqualTo("must be a hex color such as #1a2b3c");
        assertThat(fields.<String>json("$.errors['branding.accentColor']")).isNotNull();
        assertThat(fields.<String>json("$.errors['branding.email']")).isNotNull();
        assertThat(fields.<String>json("$.errors['branding.phone']")).isNotNull();
        assertThat(fields.<String>json("$.errors['branding.description']")).isNotNull();
    }

    @Test
    void uploadsAProcessedLogoAndServesItByShortLivedUrl() {
        Response tooBig = alice.post(branding + "/logo-uploads",
                "{\"filename\":\"logo.png\",\"contentType\":\"image/png\",\"byteSize\":%d}".formatted(6 * 1024 * 1024));
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.<String>json("$.errors.byteSize")).isEqualTo("must be at most 5 MB");
        assertThat(alice.post(branding + "/logo-uploads",
                "{\"filename\":\"logo.gif\",\"contentType\":\"image/gif\",\"byteSize\":100}").status()).isEqualTo(400);

        String logo = uploadLogo(alice, aliceWorkspace);
        assertThat(jdbc.queryForObject("SELECT session_id FROM photo_assets WHERE id = ?::uuid", UUID.class, logo)).isNull();

        Response saved = alice.put(branding, """
                {"version":0,"branding":{"logoAssetId":"%s","primaryColor":"#000000","accentColor":"#ffffff","font":"INTER"}}"""
                .formatted(logo));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.<String>json("$.logo.id")).isEqualTo(logo);
        assertThat(saved.<String>json("$.logo.status")).isEqualTo("READY");
        URI url = URI.create(saved.json("$.logo.url"));
        assertThat(url.getQuery()).contains("X-Amz-Expires=600");
        assertThat(ApiClient.getBytes(url).statusCode()).isEqualTo(200);
    }

    @Test
    void refusesLogosThatAreNotReadyLogosOfThisWorkspace() {
        String session = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", "{\"title\":\"Family\"}").json("$.id");
        String photo = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions/" + session + "/assets",
                "{\"filename\":\"a.jpg\",\"contentType\":\"image/jpeg\",\"byteSize\":100}").json("$.asset.id");
        String pending = alice.post(branding + "/logo-uploads",
                "{\"filename\":\"logo.png\",\"contentType\":\"image/png\",\"byteSize\":100}").json("$.asset.id");
        String bobLogo = uploadLogo(bob, bobWorkspace);

        for (String asset : new String[] {photo, pending, bobLogo, UUID.randomUUID().toString()}) {
            Response refused = alice.put(branding, """
                    {"version":0,"branding":{"logoAssetId":"%s","primaryColor":"#000000","accentColor":"#ffffff","font":"INTER"}}"""
                    .formatted(asset));
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.<String>json("$.errors.logoAssetId")).isEqualTo("must be a processed logo of this workspace");
        }
    }

    @Test
    void anyMemberCanChangeBrandingButOtherWorkspacesCannotSeeIt() {
        ApiClient carol = new ApiClient(port).signIn("carol-" + UUID.randomUUID() + "@example.com");
        String carolId = carol.get("/api/me").json("$.id");
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, carolId);
        assertThat(carol.put(branding, body(0, "#123456")).status()).isEqualTo(200);

        assertThat(bob.get(branding).status()).isEqualTo(404);
        assertThat(bob.put(branding, body(1, "#000000")).status()).isEqualTo(404);
        assertThat(bob.post(branding + "/logo-uploads",
                "{\"filename\":\"logo.png\",\"contentType\":\"image/png\",\"byteSize\":100}").status()).isEqualTo(404);
        assertThat(bob.get("/api/workspaces/" + bobWorkspace + "/branding").<String>json("$.primaryColor"))
                .isEqualTo("#18181b");
    }

    private static String body(int version, String primary) {
        return """
                {"version":%d,"branding":{"primaryColor":"%s","accentColor":"#ffffff","font":"INTER"}}"""
                .formatted(version, primary);
    }

    /** Uploads and processes a logo the way the browser does; returns its asset ID once it is ready. */
    private static String uploadLogo(ApiClient client, String workspace) {
        byte[] png = TestImages.transparentPng(300, 120);
        Response started = client.post("/api/workspaces/" + workspace + "/branding/logo-uploads",
                "{\"filename\":\"logo.png\",\"contentType\":\"image/png\",\"byteSize\":%d}".formatted(png.length));
        assertThat(started.status()).isEqualTo(201);
        String asset = started.json("$.asset.id");
        assertThat(ApiClient.putBytes(URI.create(started.json("$.upload.url")), png, started.json("$.upload.headers"))
                .status()).isEqualTo(200);
        String assetPath = "/api/workspaces/" + workspace + "/assets/" + asset;
        assertThat(client.post(assetPath + "/complete").status()).isEqualTo(200);
        Eventually.await(() -> client.get(assetPath).<String>json("$.status"), "READY"::equals);
        return asset;
    }
}
