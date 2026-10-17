package com.photospace.galleries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.shared.SecretTokens;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import com.photospace.support.TestImages;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Galleries end to end: building one from ready photos, publishing it, share links that work only for their own
 * gallery and only until they expire or are revoked, download settings, recorded access, and that no user or
 * visitor can reach another workspace's galleries.
 */
@IntegrationTest
class GalleriesIT {

    private static final String PUBLIC = "/api/public/gallery";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient alice;
    private ApiClient bob;
    private ApiClient visitor;
    private String aliceWorkspace;
    private String aliceSession;
    private String galleries;
    private String bobWorkspace;
    private String bobSession;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        visitor = new ApiClient(port);
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        aliceSession = createSession(alice, aliceWorkspace, "Family");
        galleries = "/api/workspaces/" + aliceWorkspace + "/galleries";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
        bobSession = createSession(bob, bobWorkspace, "Bob's");
    }

    @Test
    void sharesAPublishedGalleryWithAVisitor() throws Exception {
        byte[] jpeg = TestImages.jpeg(600, 400);
        String first = uploadReadyPhoto("Łazienki 1.jpg", "image/jpeg", jpeg);
        String second = uploadReadyPhoto("second.png", "image/png", TestImages.transparentPng(300, 300));

        Response created = alice.post(galleries, galleryJson(aliceSession, "Anna & family", "ORIGINALS"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.gallery.status")).isEqualTo("DRAFT");
        assertThat(created.<Integer>json("$.gallery.photoCount")).isZero();
        String galleryId = created.json("$.gallery.id");

        Response withPhotos = setPhotos(galleryId, second, first);
        assertThat(withPhotos.status()).isEqualTo(200);
        assertThat(withPhotos.<List<String>>json("$.photos[*].id")).containsExactly(second, first);
        assertThat(withPhotos.<Integer>json("$.gallery.photoCount")).isEqualTo(2);
        assertThat(publish(galleryId).<String>json("$.gallery.status")).isEqualTo("PUBLISHED");

        Response link = createLink(galleryId, 30);
        assertThat(link.status()).isEqualTo(201);
        assertThat(link.<String>json("$.link.state")).isEqualTo("ACTIVE");
        assertThat(Instant.parse(link.json("$.link.expiresAt")))
                .isBetween(Instant.now().plus(Duration.ofDays(30)).minusSeconds(60), Instant.now().plus(Duration.ofDays(30)));
        String token = link.json("$.token");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        byte[] storedHash = jdbc.queryForObject("SELECT token_hash FROM gallery_share_links WHERE id = ?::uuid",
                byte[].class, link.<String>json("$.link.id"));
        assertThat(storedHash).as("only the hash is stored")
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        assertThat(alice.get(galleries + "/" + galleryId + "/links").body()).doesNotContain(token);

        Response view = view(token);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.<String>json("$.title")).isEqualTo("Anna & family");
        assertThat(view.<String>json("$.studioName")).isEqualTo("Alice Studio");
        assertThat(view.<String>json("$.downloads")).isEqualTo("ORIGINALS");
        assertThat(view.<List<String>>json("$.photos[*].id")).containsExactly(second, first);
        assertThat(view.<Integer>json("$.photos[1].width")).isEqualTo(600);
        assertThat(view.body()).as("no originals and no file names").doesNotContain("/originals/", "Łazienki", "second.png");
        // Presigned URLs contain object keys, which include the workspace ID (an ID grants nothing without membership).
        assertThat(view.body().replaceAll("\"https?://[^\"]*\"", "\"\"")).as("no IDs beyond the photos'")
                .doesNotContain(aliceWorkspace, aliceSession, galleryId);
        assertThat(view.setCookies()).as("visitors get no session").noneMatch(c -> c.startsWith("SESSION="));
        HttpResponse<byte[]> thumbnail = ApiClient.getBytes(URI.create(view.json("$.photos[0].thumbnailUrl")));
        assertThat(thumbnail.statusCode()).isEqualTo(200);

        Response download = download(token, first);
        assertThat(download.status()).isEqualTo(200);
        HttpResponse<byte[]> original = ApiClient.getBytes(URI.create(download.json("$.url")));
        assertThat(original.statusCode()).isEqualTo(200);
        assertThat(original.body()).isEqualTo(jpeg);
        assertThat(original.headers().firstValue("Content-Disposition")).hasValue(
                "attachment; filename=\"_azienki 1.jpg\"; filename*=UTF-8''%C5%81azienki%201.jpg");
        assertThat(original.headers().firstValue("Content-Type")).hasValue("image/jpeg");

        Response links = alice.get(galleries + "/" + galleryId + "/links");
        assertThat(links.<Integer>json("$.items[0].views")).isEqualTo(1);
        assertThat(links.<Integer>json("$.items[0].downloads")).isEqualTo(1);
        assertThat(links.<String>json("$.items[0].lastAccessedAt")).isNotNull();
        assertThat(jdbc.queryForList("SELECT kind || ':' || coalesce(asset_id::text, '-') FROM gallery_access_events"
                        + " WHERE link_id = ?::uuid ORDER BY occurred_at", String.class, link.<String>json("$.link.id")))
                .containsExactly("VIEW:-", "DOWNLOAD:" + first);
    }

    @Test
    void downloadsFollowTheGallerySetting() {
        String photo = insertReadyPhoto(aliceWorkspace, aliceSession, "IMG_0042.JPG");
        String galleryId = publishedGallery("NONE", photo);
        String token = createLink(galleryId, 7).json("$.token");

        assertThat(view(token).<String>json("$.downloads")).isEqualTo("NONE");
        assertThat(download(token, photo).status()).isEqualTo(403);

        updateSettings(galleryId, "PREVIEWS");
        Response preview = download(token, photo);
        assertThat(preview.status()).isEqualTo(200);
        URI previewUrl = URI.create(preview.json("$.url"));
        assertThat(previewUrl.getPath()).endsWith("/seed/preview/" + photo);
        assertThat(query(previewUrl)).containsEntry("response-content-disposition",
                "attachment; filename=\"IMG_0042-preview.jpg\"; filename*=UTF-8''IMG_0042-preview.jpg");
        assertThat(Instant.parse(preview.json("$.expiresAt"))).isBefore(Instant.now().plus(Duration.ofMinutes(6)));

        updateSettings(galleryId, "ORIGINALS");
        URI originalUrl = URI.create(download(token, photo).json("$.url"));
        assertThat(originalUrl.getPath()).endsWith("/seed/original/" + photo);
        assertThat(query(originalUrl)).containsEntry("response-content-type", "image/jpeg");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM gallery_access_events WHERE kind = 'DOWNLOAD'"
                + " AND asset_id = ?::uuid", Long.class, photo)).as("the refused download is not recorded").isEqualTo(2);
    }

    @Test
    void linksStopWorkingWhenUnpublishedArchivedExpiredOrRevoked() {
        String photo = insertReadyPhoto(aliceWorkspace, aliceSession, "a.jpg");
        String galleryId = publishedGallery("PREVIEWS", photo);
        String token = createLink(galleryId, 7).json("$.token");
        String expiringToken = createLink(galleryId, 7).json("$.token");
        Response revocable = createLink(galleryId, 7);
        String revokedToken = revocable.json("$.token");
        assertThat(view(token).status()).isEqualTo(200);

        alice.post(galleries + "/" + galleryId + "/status", "{\"status\":\"DRAFT\"}");
        assertNotFound(view(token));
        assertThat(publish(galleryId).status()).isEqualTo(200);
        assertThat(view(token).status()).isEqualTo(200);

        alice.post(galleries + "/" + galleryId + "/archive");
        assertNotFound(view(token));
        assertNotFound(download(token, photo));
        alice.post(galleries + "/" + galleryId + "/restore");
        assertThat(view(token).status()).as("restored and still published").isEqualTo(200);

        jdbc.update("UPDATE gallery_share_links SET created_at = now() - interval '8 days',"
                + " expires_at = now() - interval '1 second' WHERE token_hash = ?", (Object) SecretTokens.hash(expiringToken));
        assertNotFound(view(expiringToken));
        assertNotFound(download(expiringToken, photo));

        String revokePath = galleries + "/" + galleryId + "/links/" + revocable.json("$.link.id") + "/revoke";
        Response revoked = alice.post(revokePath);
        assertThat(revoked.<String>json("$.state")).isEqualTo("REVOKED");
        assertNotFound(view(revokedToken));
        assertNotFound(download(revokedToken, photo));
        Response revokedAgain = alice.post(revokePath);
        assertThat(revokedAgain.status()).isEqualTo(200);
        assertThat(revokedAgain.<String>json("$.revokedAt")).isEqualTo(revoked.json("$.revokedAt"));

        assertThat(alice.get(galleries + "/" + galleryId + "/links").<List<String>>json("$.items[*].state"))
                .containsExactlyInAnyOrder("ACTIVE", "EXPIRED", "REVOKED");
        assertThat(view(token).status()).as("the other links are unaffected").isEqualTo(200);

        // Publishing and links are audited; the repeated revoke is not, and no event holds a token.
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT action, detail FROM audit_events WHERE workspace_id = ?::uuid ORDER BY created_at, id""",
                aliceWorkspace);
        assertThat(events).extracting(e -> e.get("action")).containsExactly("GALLERY_PUBLISHED",
                "SHARE_LINK_CREATED", "SHARE_LINK_CREATED", "SHARE_LINK_CREATED", "GALLERY_UNPUBLISHED",
                "GALLERY_PUBLISHED", "SHARE_LINK_REVOKED");
        assertThat(events).extracting(e -> e.get("detail")).contains("valid 7 days");
        assertThat(events.toString()).doesNotContain(token, revokedToken, expiringToken);
    }

    @Test
    void malformedUnknownAndMissingTokensAllGetTheSame404() {
        String photo = insertReadyPhoto(aliceWorkspace, aliceSession, "a.jpg");
        String galleryId = publishedGallery("ORIGINALS", photo);
        String token = createLink(galleryId, 7).json("$.token");

        List<String> invalid = List.of(
                "",
                "abc",
                token + "x",
                token.substring(1),
                SecretTokens.generate(), // well formed, unknown
                "'; DROP TABLE galleries; --" + "a".repeat(16),
                HexFormat.of().formatHex(SecretTokens.hash(token)).substring(0, 43)); // the stored hash is not a token
        for (String candidate : invalid) {
            assertNotFound(view(candidate));
            assertNotFound(download(candidate, photo));
        }
        assertNotFound(visitor.get(PUBLIC));
        assertNotFound(visitor.post(PUBLIC + "/photos/" + photo + "/download"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gallery_access_events e JOIN gallery_share_links l"
                + " ON l.id = e.link_id WHERE l.gallery_id = ?::uuid", Long.class, galleryId)).isZero();
    }

    @Test
    void aTokenOpensOnlyItsOwnGalleryAndNothingElse() {
        String inGallery = insertReadyPhoto(aliceWorkspace, aliceSession, "in.jpg");
        String elsewhere = insertReadyPhoto(aliceWorkspace, aliceSession, "elsewhere.jpg");
        String galleryId = publishedGallery("ORIGINALS", inGallery);
        publishedGallery("ORIGINALS", elsewhere);
        String bobPhoto = insertReadyPhoto(bobWorkspace, bobSession, "bob.jpg");
        String token = createLink(galleryId, 7).json("$.token");

        assertThat(download(token, inGallery).status()).isEqualTo(200);
        assertNotFound(download(token, elsewhere));
        assertNotFound(download(token, bobPhoto));
        assertNotFound(download(token, UUID.randomUUID().toString()));
        assertNotFound(download(token, "not-a-uuid"));
        assertThat(view(token).<List<String>>json("$.photos[*].id")).containsExactly(inGallery);

        // The token is not a credential for anything else, with or without the header.
        Map<String, String> header = Map.of(PublicGalleryController.TOKEN_HEADER, token);
        assertThat(visitor.getWithHeaders("/api/me", header).status()).isEqualTo(401);
        assertThat(visitor.getWithHeaders(galleries, header).status()).isEqualTo(401);
        assertThat(visitor.getWithHeaders(galleries + "/" + galleryId, header).status()).isEqualTo(401);
        assertThat(visitor.getWithHeaders("/api/workspaces/" + aliceWorkspace + "/sessions/" + aliceSession + "/assets", header)
                .status()).isEqualTo(401);
    }

    @Test
    void buildsGalleriesOnlyFromReadyPhotosOfTheirSession() {
        String ready = insertReadyPhoto(aliceWorkspace, aliceSession, "ready.jpg");
        String otherSession = createSession(alice, aliceWorkspace, "Wedding");
        String otherSessionPhoto = insertReadyPhoto(aliceWorkspace, otherSession, "wedding.jpg");
        String processing = insertPhoto(aliceWorkspace, aliceSession, "PROCESSING");
        String bobPhoto = insertReadyPhoto(bobWorkspace, bobSession, "bob.jpg");
        String galleryId = alice.post(galleries, galleryJson(aliceSession, "Proofs", "NONE")).json("$.gallery.id");

        for (List<String> ids : List.of(
                List.of(ready, ready),
                List.of(ready, processing),
                List.of(otherSessionPhoto),
                List.of(bobPhoto),
                List.of(UUID.randomUUID().toString()))) {
            Response refused = setPhotos(galleryId, ids.toArray(String[]::new));
            assertThat(refused.status()).as("%s", ids).isEqualTo(400);
            assertThat(refused.<String>json("$.errors.assetIds")).isNotBlank();
        }
        assertThat(alice.put(galleries + "/" + galleryId + "/photos", "{\"version\":0,\"assetIds\":[null]}").status())
                .isEqualTo(400);
        assertThat(alice.put(galleries + "/" + galleryId + "/photos", "{\"version\":0}").status()).isEqualTo(400);
        assertThat(alice.post(galleries + "/" + galleryId + "/status", "{\"status\":\"PUBLISHED\"}").status())
                .as("an empty gallery cannot be published").isEqualTo(409);

        assertThat(setPhotos(galleryId, ready).status()).isEqualTo(200);
        Response stale = alice.put(galleries + "/" + galleryId + "/photos", "{\"version\":0,\"assetIds\":[]}");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(publish(galleryId).status()).isEqualTo(200);
        assertThat(setPhotos(galleryId).status()).as("a published gallery cannot be emptied").isEqualTo(409);

        // The database refuses a photo of another session or workspace even if the service let one through.
        for (String foreign : List.of(otherSessionPhoto, bobPhoto)) {
            assertThatThrownBy(() -> jdbc.update("""
                            INSERT INTO gallery_assets (gallery_id, asset_id, workspace_id, session_id, position)
                            SELECT g.id, ?::uuid, g.workspace_id, g.session_id, 5 FROM galleries g WHERE g.id = ?::uuid
                            """, foreign, galleryId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void validatesGalleriesAndLinks() {
        String archivedSession = createSession(alice, aliceWorkspace, "Old");
        alice.post("/api/workspaces/" + aliceWorkspace + "/sessions/" + archivedSession + "/archive");

        assertThat(alice.post(galleries, galleryJson(bobSession, "x", "NONE")).<String>json("$.errors.sessionId"))
                .isNotBlank();
        assertThat(alice.post(galleries, galleryJson(UUID.randomUUID().toString(), "x", "NONE")).status()).isEqualTo(400);
        assertThat(alice.post(galleries, galleryJson(archivedSession, "x", "NONE")).status()).isEqualTo(409);
        assertThat(alice.post(galleries, galleryJson(aliceSession, " ", "NONE")).<String>json("$.errors['gallery.title']"))
                .isNotBlank();
        assertThat(alice.post(galleries, "{\"sessionId\":\"%s\",\"gallery\":{\"title\":\"x\",\"downloads\":\"ALL\"}}"
                .formatted(aliceSession)).status()).isEqualTo(400);

        String photo = insertReadyPhoto(aliceWorkspace, aliceSession, "a.jpg");
        String galleryId = publishedGallery("NONE", photo);
        for (String days : List.of("0", "366", "null", "-1")) {
            assertThat(alice.post(galleries + "/" + galleryId + "/links", "{\"validDays\":" + days + "}").status())
                    .as(days).isEqualTo(400);
        }
        assertThat(createLink(galleryId, 365).status()).isEqualTo(201);

        Response stale = alice.put(galleries + "/" + galleryId, "{\"version\":0,\"gallery\":{\"title\":\"New\",\"downloads\":\"NONE\"}}");
        assertThat(stale.status()).isEqualTo(409);

        alice.post(galleries + "/" + galleryId + "/archive");
        int version = alice.get(galleries + "/" + galleryId).json("$.gallery.version");
        assertThat(alice.put(galleries + "/" + galleryId, "{\"version\":%d,\"gallery\":{\"title\":\"New\",\"downloads\":\"NONE\"}}"
                .formatted(version)).status()).isEqualTo(409);
        assertThat(alice.put(galleries + "/" + galleryId + "/photos", "{\"version\":%d,\"assetIds\":[]}".formatted(version))
                .status()).isEqualTo(409);
        assertThat(alice.post(galleries + "/" + galleryId + "/status", "{\"status\":\"DRAFT\"}").status()).isEqualTo(409);
        assertThat(createLink(galleryId, 7).status()).isEqualTo(409);
        assertThat(alice.get(galleries).<List<String>>json("$.items[*].id")).doesNotContain(galleryId);
        assertThat(alice.get(galleries + "?archived=true").<List<String>>json("$.items[*].id")).containsExactly(galleryId);
    }

    @Test
    void listsGalleriesNewestFirstAndBySession() {
        String otherSession = createSession(alice, aliceWorkspace, "Wedding");
        String first = alice.post(galleries, galleryJson(aliceSession, "First", "NONE")).json("$.gallery.id");
        String second = alice.post(galleries, galleryJson(otherSession, "Second", "NONE")).json("$.gallery.id");

        assertThat(alice.get(galleries).<List<String>>json("$.items[*].id")).containsExactly(second, first);
        assertThat(alice.get(galleries + "?sessionId=" + aliceSession).<List<String>>json("$.items[*].id"))
                .containsExactly(first);
        assertThat(alice.get(galleries + "?sessionId=" + bobSession).<Integer>json("$.totalItems")).isZero();
        assertThat(alice.get(galleries + "?size=101").status()).isEqualTo(400);
        assertThat(alice.get(galleries + "?sessionId=nope").status()).isEqualTo(400);
        assertThat(bob.get("/api/workspaces/" + bobWorkspace + "/galleries").<Integer>json("$.totalItems")).isZero();
    }

    @Test
    void otherWorkspacesCannotSeeOrChangeAGallery() {
        String photo = insertReadyPhoto(aliceWorkspace, aliceSession, "a.jpg");
        String galleryId = publishedGallery("ORIGINALS", photo);
        Response link = createLink(galleryId, 7);
        String linkId = link.json("$.link.id");
        String token = link.json("$.token");
        Map<String, Object> before = galleryState(galleryId);

        String bobGalleries = "/api/workspaces/" + bobWorkspace + "/galleries";
        for (String base : List.of(galleries, bobGalleries)) {
            String path = base + "/" + galleryId;
            assertNotFound(bob.get(path));
            assertNotFound(bob.put(path, "{\"version\":%s,\"gallery\":{\"title\":\"Mine\",\"downloads\":\"NONE\"}}"
                    .formatted(before.get("version"))));
            assertNotFound(bob.put(path + "/photos", "{\"version\":%s,\"assetIds\":[]}".formatted(before.get("version"))));
            assertNotFound(bob.post(path + "/status", "{\"status\":\"DRAFT\"}"));
            assertNotFound(bob.post(path + "/archive"));
            assertNotFound(bob.post(path + "/restore"));
            assertNotFound(bob.get(path + "/links"));
            assertNotFound(bob.post(path + "/links", "{\"validDays\":7}"));
            assertNotFound(bob.post(path + "/links/" + linkId + "/revoke"));
        }
        assertNotFound(bob.get(galleries));
        assertNotFound(bob.post(galleries, galleryJson(aliceSession, "Mine", "NONE")));
        assertThat(bob.post(bobGalleries, galleryJson(aliceSession, "Mine", "NONE")).<String>json("$.errors.sessionId"))
                .as("alice's session is not bob's").isNotBlank();

        assertThat(galleryState(galleryId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gallery_share_links WHERE gallery_id = ?::uuid",
                Long.class, galleryId)).isEqualTo(1);
        assertThat(view(token).status()).as("the link still works").isEqualTo(200);
    }

    // --- Helpers ---

    private static String createSession(ApiClient client, String workspaceId, String title) {
        return client.post("/api/workspaces/" + workspaceId + "/sessions", "{\"title\":\"" + title + "\"}").json("$.id");
    }

    private static String galleryJson(String sessionId, String title, String downloads) {
        return "{\"sessionId\":\"%s\",\"gallery\":{\"title\":\"%s\",\"downloads\":\"%s\"}}"
                .formatted(sessionId, title, downloads);
    }

    /** A published gallery of alice's session holding {@code photos}. */
    private String publishedGallery(String downloads, String... photos) {
        String galleryId = alice.post(galleries, galleryJson(aliceSession, "Gallery", downloads)).json("$.gallery.id");
        assertThat(setPhotos(galleryId, photos).status()).isEqualTo(200);
        assertThat(publish(galleryId).status()).isEqualTo(200);
        return galleryId;
    }

    private Response setPhotos(String galleryId, String... assetIds) {
        int version = alice.get(galleries + "/" + galleryId).json("$.gallery.version");
        String ids = Arrays.stream(assetIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return alice.put(galleries + "/" + galleryId + "/photos",
                "{\"version\":%d,\"assetIds\":[%s]}".formatted(version, ids));
    }

    private void updateSettings(String galleryId, String downloads) {
        int version = alice.get(galleries + "/" + galleryId).json("$.gallery.version");
        Response updated = alice.put(galleries + "/" + galleryId,
                "{\"version\":%d,\"gallery\":{\"title\":\"Gallery\",\"downloads\":\"%s\"}}".formatted(version, downloads));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.<String>json("$.gallery.downloads")).isEqualTo(downloads);
    }

    private Response publish(String galleryId) {
        return alice.post(galleries + "/" + galleryId + "/status", "{\"status\":\"PUBLISHED\"}");
    }

    private Response createLink(String galleryId, int validDays) {
        return alice.post(galleries + "/" + galleryId + "/links", "{\"validDays\":" + validDays + "}");
    }

    private Response view(String token) {
        return visitor.getWithHeaders(PUBLIC, Map.of(PublicGalleryController.TOKEN_HEADER, token));
    }

    private Response download(String token, String assetId) {
        return visitor.postWithHeaders(PUBLIC + "/photos/" + assetId + "/download",
                Map.of(PublicGalleryController.TOKEN_HEADER, token));
    }

    private static void assertNotFound(Response response) {
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.<String>json("$.title")).isEqualTo("Not Found");
        assertThat(response.body()).doesNotContain("\"detail\"");
    }

    private Map<String, Object> galleryState(String galleryId) {
        return jdbc.queryForMap("""
                SELECT g.title, g.status, g.downloads, g.archived_at, g.version,
                       (SELECT count(*) FROM gallery_assets ga WHERE ga.gallery_id = g.id) AS photos,
                       (SELECT count(*) FROM gallery_share_links l WHERE l.gallery_id = g.id AND l.revoked_at IS NULL) AS live_links
                  FROM galleries g WHERE g.id = ?::uuid
                """, galleryId);
    }

    /** A photo uploaded and processed through the real API, storage emulator, and worker. */
    private String uploadReadyPhoto(String filename, String contentType, byte[] content) {
        String assets = "/api/workspaces/" + aliceWorkspace + "/assets";
        Response started = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions/" + aliceSession + "/assets",
                "{\"filename\":\"%s\",\"contentType\":\"%s\",\"byteSize\":%d}".formatted(filename, contentType, content.length));
        assertThat(started.status()).isEqualTo(201);
        assertThat(ApiClient.putBytes(URI.create(started.json("$.upload.url")), content, started.json("$.upload.headers"))
                .status()).isEqualTo(200);
        String assetId = started.json("$.asset.id");
        assertThat(alice.post(assets + "/" + assetId + "/complete").status()).isEqualTo(200);
        Response ready = Eventually.await(() -> alice.get(assets + "/" + assetId),
                response -> !List.of("PENDING_UPLOAD", "PROCESSING").contains(response.<String>json("$.status")));
        assertThat(ready.<String>json("$.status")).isEqualTo("READY");
        return assetId;
    }

    /** A ready photo row whose files do not exist: enough wherever only URLs are signed, never fetched. */
    private String insertReadyPhoto(String workspaceId, String sessionId, String filename) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO photo_assets (id, workspace_id, session_id, status, original_filename, content_type,
                                                  byte_size, original_key, thumbnail_key, preview_key, checksum_sha256,
                                                  width, height)
                        VALUES (?, ?::uuid, ?::uuid, 'READY', ?, 'image/jpeg', 100, ?, ?, ?, ?, 4, 3)
                        """, id, workspaceId, sessionId, filename, "seed/original/" + id, "seed/thumbnail/" + id,
                "seed/preview/" + id, "0".repeat(64));
        return id.toString();
    }

    private String insertPhoto(String workspaceId, String sessionId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO photo_assets (id, workspace_id, session_id, status, original_filename, content_type,
                                                  byte_size, original_key)
                        VALUES (?, ?::uuid, ?::uuid, ?, 'pending.jpg', 'image/jpeg', 100, ?)
                        """, id, workspaceId, sessionId, status, "seed/original/" + id);
        return id.toString();
    }

    private static Map<String, String> query(URI url) {
        return Arrays.stream(url.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> pair[0], pair -> URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8)));
    }
}
