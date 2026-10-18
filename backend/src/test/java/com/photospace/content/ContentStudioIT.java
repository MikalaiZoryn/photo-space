package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.storage.ObjectStorage;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import com.photospace.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Content Studio end to end, with the real job worker and the scriptable mock provider: settings, automatic
 * plans once photos settle, dates fixed by gallery publication, post photos and crops, approval, the post kit,
 * marking posts as posted, refreshing a plan, rewriting one post, adding a post, the hashtag cap, and workspace
 * isolation.
 */
@IntegrationTest
class ContentStudioIT {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private PlanProposalScheduler scheduler;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String aliceSession;
    private String content;
    private String bobWorkspace;

    @BeforeEach
    void setUp() {
        TestContentProvider.FAILURES.clear();
        TestContentProvider.PLAN_REQUESTS.clear();
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        String client = alice.post("/api/workspaces/" + aliceWorkspace + "/clients",
                "{\"name\":\"Anna Nowak\",\"email\":\"anna.nowak@example.com\"}").json("$.id");
        aliceSession = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", """
                {"title":"Anna Nowak family","sessionType":"Family","clientId":"%s","location":"Private garden",
                 "notes":"Session notes that are never sent"}""".formatted(client)).json("$.id");
        content = "/api/workspaces/" + aliceWorkspace + "/content";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
    }

    @Test
    void settingsHaveDefaultsAndOptimisticLocking() {
        Response defaults = alice.get(content + "/studio-settings");
        assertThat(defaults.<Integer>json("$.weeklyGoal")).isEqualTo(4);
        assertThat(defaults.<Boolean>json("$.autoPlans")).isTrue();
        assertThat(defaults.<Integer>json("$.dripPosts")).isEqualTo(6);
        assertThat(defaults.<Integer>json("$.version")).isZero();

        Response saved = saveSettings(0, 3, true, " Kraków ");
        assertThat(saved.<Integer>json("$.weeklyGoal")).isEqualTo(3);
        assertThat(saved.<String>json("$.studioArea")).isEqualTo("Kraków");
        assertThat(saved.<Integer>json("$.version")).isEqualTo(1);
        assertThat(saveSettings(0, 5, true, null).status()).as("stale version").isEqualTo(409);
        assertThat(alice.put(content + "/studio-settings", """
                {"version":1,"settings":{"weeklyGoal":15,"autoPlans":true,"sneakPeekDays":1,"dripPosts":6,
                 "dripSpacingDays":7}}""").<String>json("$.errors['settings.weeklyGoal']")).isNotNull();
        assertThat(alice.get(content + "/studio-settings").<Integer>json("$.weeklyGoal")).isEqualTo(3);
    }

    @Test
    void proposesAPlanOnceThePhotosSettleAndDatesItFromTheGallery() {
        saveSettings(0, 4, true, "Kraków");
        List<String> photos = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            photos.add(readyPhoto(aliceSession, 40));
        }

        assertThat(scheduler.proposeSettled()).isGreaterThanOrEqualTo(1);
        assertThat(scheduler.proposeSettled()).as("one plan per session").isZero();
        Response plan = awaitPlan(aliceSession);
        assertThat(plan.<String>json("$.plan.status")).isEqualTo("SUCCEEDED");
        assertThat(plan.<String>json("$.plan.origin")).isEqualTo("AUTOMATIC");
        assertThat(plan.<String>json("$.plan.publishedOn")).isNull();
        assertThat(plan.<List<String>>json("$.posts[*].stage")).as("dated first, then by stage").containsExactly(
                "SNEAK_PEEK", "SNEAK_PEEK", "DELIVERY", "DELIVERY", "DELIVERY", "DRIP", "DRIP", "DRIP", "DRIP",
                "DRIP", "DRIP");
        assertThat(plan.<List<String>>json("$.posts[*].status")).containsOnly("PROPOSED");

        // What the provider received: the photos, the studio area, the session type, and the slots. No client
        // details, session title, location, or session notes.
        PlanRequest sent = TestContentProvider.PLAN_REQUESTS.getFirst();
        assertThat(sent.photos()).hasSize(12);
        assertThat(sent.studioArea()).isEqualTo("Kraków");
        assertThat(sent.sessionType()).isEqualTo("Family");
        assertThat(sent.slots()).hasSize(11);
        assertThat(String.join("|", sent.brand().toString(), sent.sessionType(), sent.studioArea()))
                .doesNotContain("Anna", "Nowak", "Private garden", "Session notes");

        LocalDate today = LocalDate.now(WARSAW);
        Response sneakPeek = alice.get(content + "/posts?sessionId=" + aliceSession + "&status=PROPOSED&size=50");
        assertThat(sneakPeek.<String>json("$.items[0].plannedOn")).isEqualTo(today.plusDays(1).toString());
        assertThat(sneakPeek.<String>json("$.items[0].format")).isEqualTo("SINGLE");
        assertThat(sneakPeek.<String>json("$.items[0].coverThumbnailUrl")).isNotNull();
        assertThat(sneakPeek.<Integer>json("$.totalItems")).isEqualTo(11);

        publishGallery(aliceSession, photos.subList(0, 2));
        Response dated = alice.get(content + "/sessions/" + aliceSession + "/plan");
        assertThat(dated.<String>json("$.plan.publishedOn")).isEqualTo(today.toString());
        assertThat(dated.<List<String>>json("$.posts[*].plannedOn")).doesNotContainNull();
        assertThat(dated.<List<String>>json("$.posts[?(@.format == 'REEL')].plannedOn"))
                .containsExactly(today.plusDays(2).toString());
        assertThat(dated.<List<String>>json("$.posts[?(@.stage == 'DRIP')].plannedOn"))
                .containsExactly(today.plusDays(7).toString(), today.plusDays(14).toString(),
                        today.plusDays(21).toString(), today.plusDays(28).toString(), today.plusDays(35).toString(),
                        today.plusDays(42).toString());

        Response week = alice.get(content + "/posts?from=" + today + "&to=" + today.plusDays(6));
        assertThat(week.<List<String>>json("$.items[*].stage")).containsExactly(
                "DELIVERY", "DELIVERY", "SNEAK_PEEK", "SNEAK_PEEK", "DELIVERY");
        assertThat(alice.get(content + "/posts?from=" + today + "&to=" + today.plusDays(90)).status())
                .as("ranges are capped").isEqualTo(400);
        assertThat(alice.get(content + "/plans?toReview=true").<List<String>>json("$.items[*].sessionId"))
                .containsExactly(aliceSession);
    }

    @Test
    void waitsForPhotosToSettleAndRespectsTheSwitch() {
        readyPhoto(aliceSession, 10);
        assertThat(scheduler.proposeSettled()).isZero();
        assertThat(alice.get(content + "/sessions/" + aliceSession + "/plan").status()).isEqualTo(404);

        String oldSession = session("Last year");
        readyPhoto(oldSession, 60 * 24 * 10);
        scheduler.proposeSettled();
        assertThat(alice.get(content + "/sessions/" + oldSession + "/plan").status()).as("outside the lookback")
                .isEqualTo(404);

        String settled = session("Settled");
        saveSettings(0, 4, false, null);
        readyPhoto(settled, 45);
        scheduler.proposeSettled();
        assertThat(alice.get(content + "/sessions/" + settled + "/plan").status()).as("turned off").isEqualTo(404);
    }

    @Test
    void cropsApprovesKitsAndMarksAPostAsPosted() throws IOException {
        String first = readyPhoto(aliceSession, 0);
        String second = readyPhoto(aliceSession, 0);
        Response plan = requestPlan(aliceSession);
        String postId = plan.json("$.posts[0].id");
        String posts = content + "/posts/" + postId;

        Response detail = alice.get(posts);
        assertThat(detail.<Double>json("$.photos[0].crop.width")).isEqualTo(0.6, offset(1e-9));
        assertThat(detail.<Boolean>json("$.photos[0].customCrop")).isFalse();
        assertThat(detail.<List<String>>json("$.draft.rules[*].code")).contains("LOCATION");
        String draftId = detail.json("$.post.draftId");
        int draftVersion = detail.json("$.post.draftVersion");

        assertThat(alice.get(posts + "/kit").status()).as("not approved yet").isEqualTo(409);
        assertThat(alice.post(posts + "/posted", "{\"version\":0,\"posted\":true,\"postedOn\":\"2026-10-20\"}").status())
                .isEqualTo(409);

        assertThat(alice.post(content + "/drafts/" + draftId + "/status",
                "{\"version\":" + draftVersion + ",\"status\":\"APPROVED\"}").<String>json("$.status")).isEqualTo("APPROVED");
        assertThat(alice.get(posts).<String>json("$.post.status")).isEqualTo("SCHEDULED");

        // A crop that is not 4:5 is refused; a valid one is kept and takes the approval back.
        assertThat(alice.put(posts + "/photos", """
                {"version":0,"photos":[{"assetId":"%s","crop":{"x":0,"y":0,"width":1,"height":1}}]}""".formatted(first))
                .<String>json("$.errors.photos")).contains("4:5");
        Response cropped = alice.put(posts + "/photos", """
                {"version":0,"photos":[{"assetId":"%s","crop":{"x":0.1,"y":0,"width":0.6,"height":1}},
                 {"assetId":"%s"}]}""".formatted(second, first));
        assertThat(cropped.status()).isEqualTo(200);
        assertThat(cropped.<String>json("$.post.format")).isEqualTo("CAROUSEL");
        assertThat(cropped.<List<String>>json("$.photos[*].photo.id")).containsExactly(second, first);
        assertThat(cropped.<Boolean>json("$.photos[0].customCrop")).isTrue();
        assertThat(cropped.<String>json("$.post.status")).as("approval covered other photos").isEqualTo("PROPOSED");

        alice.post(content + "/drafts/" + draftId + "/status",
                "{\"version\":" + draftVersion + ",\"status\":\"APPROVED\"}");
        Response kit = alice.get(posts + "/kit");
        assertThat(kit.status()).isEqualTo(200);
        assertThat(kit.<List<String>>json("$.photos[*].assetId")).containsExactly(second, first);
        HttpResponse<byte[]> export = ApiClient.getBytes(URI.create(kit.json("$.photos[0].exportUrl")));
        assertThat(export.statusCode()).isEqualTo(200);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(export.body()));
        assertThat(image.getWidth() / (double) image.getHeight()).isCloseTo(0.8, offset(0.02));
        assertThat(ApiClient.getBytes(URI.create(kit.json("$.photos[0].originalUrl"))).statusCode()).isEqualTo(200);

        int version = alice.get(posts).json("$.post.version");
        assertThat(alice.post(posts + "/posted", """
                {"version":%d,"posted":true,"postedOn":"2026-10-20","postUrl":"javascript:alert(1)"}""".formatted(version))
                .<String>json("$.errors.postUrl")).isNotNull();
        Response posted = alice.post(posts + "/posted", """
                {"version":%d,"posted":true,"postedOn":"2026-10-20","postUrl":"https://instagram.com/p/abc"}"""
                .formatted(version));
        assertThat(posted.<String>json("$.status")).isEqualTo("POSTED");
        assertThat(posted.<String>json("$.postedOn")).isEqualTo("2026-10-20");
        assertThat(alice.delete(posts + "?version=" + posted.<Integer>json("$.version")).status())
                .as("a posted post stays").isEqualTo(409);
        assertThat(alice.get(content + "/posts?status=POSTED").<List<String>>json("$.items[*].id")).containsExactly(postId);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE target_id = ?::uuid", String.class, postId))
                .containsExactly("CONTENT_POST_POSTED");

        Response unposted = alice.post(posts + "/posted",
                "{\"version\":" + posted.<Integer>json("$.version") + ",\"posted\":false}");
        assertThat(unposted.<String>json("$.status")).isEqualTo("SCHEDULED");
        assertThat(alice.delete(posts + "?version=" + unposted.<Integer>json("$.version")).status()).isEqualTo(204);
        assertThat(alice.get(posts).status()).isEqualTo(404);
    }

    @Test
    void refreshKeepsWhatAPersonTouchedAndReplacesTheRest() {
        for (int i = 0; i < 8; i++) {
            readyPhoto(aliceSession, 0);
        }
        Response plan = requestPlan(aliceSession);
        assertThat(plan.<String>json("$.plan.origin")).isEqualTo("MANUAL");
        String kept = plan.json("$.posts[0].id");
        String replaced = plan.json("$.posts[1].id");
        assertThat(alice.put(content + "/posts/" + kept + "/schedule", "{\"version\":0,\"plannedOn\":\"2026-11-02\"}")
                .<String>json("$.plannedOn")).isEqualTo("2026-11-02");

        Response refreshed = alice.post(content + "/sessions/" + aliceSession + "/plan");
        assertThat(refreshed.<String>json("$.plan.status")).isEqualTo("PENDING");
        Response done = awaitPlan(aliceSession);
        List<String> ids = done.json("$.posts[*].id");
        assertThat(ids).contains(kept).doesNotContain(replaced);
        assertThat(done.<List<String>>json("$.posts[?(@.stage == 'SNEAK_PEEK' && @.format != 'STORY')].id"))
                .as("the kept sneak peek is not written again").containsExactly(kept);
        PlanRequest second = TestContentProvider.PLAN_REQUESTS.getLast();
        assertThat(second.slots()).hasSize(10);
    }

    @Test
    void rewritesOnePostAndAddsAPostAPersonWrote() {
        String photo = readyPhoto(aliceSession, 0);
        Response plan = requestPlan(aliceSession);
        String postId = plan.json("$.posts[0].id");
        String captionBefore = plan.json("$.posts[0].caption");

        Response queued = alice.post(content + "/posts/" + postId + "/regenerate", "{\"version\":0}");
        assertThat(queued.<Boolean>json("$.regenerating")).isTrue();
        Response rewritten = Eventually.await(() -> alice.get(content + "/posts/" + postId),
                r -> !r.<Boolean>json("$.post.regenerating"));
        assertThat(rewritten.<Integer>json("$.post.draftVersion")).isEqualTo(2);
        assertThat(rewritten.<String>json("$.post.caption")).isNotEqualTo(captionBefore);
        assertThat(rewritten.<String>json("$.draft.source")).isEqualTo("GENERATED");

        String planId = plan.json("$.plan.id");
        Response added = alice.post(content + "/plans/" + planId + "/posts", """
                {"stage":"DRIP","format":"SINGLE","photos":["%s"],"plannedOn":"2026-12-01",
                 "draft":{"caption":"A family favourite from Kraków.","callToAction":"Send this to a friend.",
                          "hashtags":["family"]}}""".formatted(photo));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.<String>json("$.draft.source")).isEqualTo("EDITED");
        assertThat(added.<String>json("$.post.plannedOn")).isEqualTo("2026-12-01");
        assertThat(alice.post(content + "/plans/" + planId + "/posts", """
                {"stage":"DRIP","format":"SINGLE","photos":["%s"],
                 "draft":{"caption":"x","hashtags":["a","b","c","d","e","f"]}}""".formatted(photo)).status())
                .as("at most 5 hashtags").isEqualTo(400);
    }

    @Test
    void reportsAPermanentFailureAndRetries() {
        readyPhoto(aliceSession, 0);
        TestContentProvider.FAILURES.add(Kind.REFUSED);
        alice.post(content + "/sessions/" + aliceSession + "/plan");
        Response failed = awaitPlan(aliceSession);
        assertThat(failed.<String>json("$.plan.status")).isEqualTo("FAILED");
        assertThat(failed.<String>json("$.plan.failureReason")).isEqualTo(Kind.REFUSED.userMessage());

        alice.post(content + "/plans/" + failed.<String>json("$.plan.id") + "/retry");
        assertThat(awaitPlan(aliceSession).<String>json("$.plan.status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void theCaptionBatchesListLeavesPlansOut() {
        readyPhoto(aliceSession, 0);
        requestPlan(aliceSession);

        assertThat(alice.get(content + "/batches").<Integer>json("$.totalItems")).isZero();
        assertThat(alice.post(content + "/batches/" + alice.get(content + "/sessions/" + aliceSession + "/plan")
                .<String>json("$.plan.batchId") + "/regenerate").status()).isEqualTo(409);
    }

    @Test
    void otherWorkspacesCannotReachTheContentStudio() {
        String photo = readyPhoto(aliceSession, 0);
        Response plan = requestPlan(aliceSession);
        String postId = plan.json("$.posts[0].id");
        String planId = plan.json("$.plan.id");

        for (String base : List.of(content, "/api/workspaces/" + bobWorkspace + "/content")) {
            assertThat(bob.get(base + "/sessions/" + aliceSession + "/plan").status()).isEqualTo(404);
            assertThat(bob.post(base + "/sessions/" + aliceSession + "/plan").status()).isEqualTo(404);
            assertThat(bob.post(base + "/plans/" + planId + "/retry").status()).isEqualTo(404);
            assertThat(bob.post(base + "/plans/" + planId + "/posts", """
                    {"stage":"DRIP","format":"SINGLE","photos":["%s"],"draft":{"caption":"x","hashtags":[]}}"""
                    .formatted(photo)).status()).isEqualTo(404);
            assertThat(bob.get(base + "/posts/" + postId).status()).isEqualTo(404);
            assertThat(bob.get(base + "/posts/" + postId + "/kit").status()).isEqualTo(404);
            assertThat(bob.put(base + "/posts/" + postId + "/schedule", "{\"version\":0,\"plannedOn\":null}").status())
                    .isEqualTo(404);
            assertThat(bob.put(base + "/posts/" + postId + "/photos",
                    "{\"version\":0,\"photos\":[{\"assetId\":\"" + photo + "\"}]}").status()).isEqualTo(404);
            assertThat(bob.post(base + "/posts/" + postId + "/posted", "{\"version\":0,\"posted\":false}").status())
                    .isEqualTo(404);
            assertThat(bob.post(base + "/posts/" + postId + "/regenerate", "{\"version\":0}").status()).isEqualTo(404);
            assertThat(bob.delete(base + "/posts/" + postId + "?version=0").status()).isEqualTo(404);
        }
        assertThat(bob.get(content + "/studio-settings").status()).isEqualTo(404);
        assertThat(bob.get(content + "/plans").status()).isEqualTo(404);
        assertThat(bob.get(content + "/posts").status()).isEqualTo(404);
        String bobContent = "/api/workspaces/" + bobWorkspace + "/content";
        assertThat(bob.get(bobContent + "/posts").<Integer>json("$.totalItems")).isZero();
        assertThat(bob.get(bobContent + "/plans").<Integer>json("$.totalItems")).isZero();

        Response unchanged = alice.get(content + "/posts/" + postId);
        assertThat(unchanged.<Integer>json("$.post.version")).isZero();
        assertThat(unchanged.<Boolean>json("$.post.regenerating")).isFalse();
    }

    // --- Helpers. ---

    private Response saveSettings(int version, int weeklyGoal, boolean autoPlans, String studioArea) {
        return alice.put(content + "/studio-settings", """
                {"version":%d,"settings":{"weeklyGoal":%d,"autoPlans":%b,"sneakPeekDays":1,"dripPosts":6,
                 "dripSpacingDays":7,"studioArea":%s}}""".formatted(version, weeklyGoal, autoPlans,
                studioArea == null ? "null" : "\"" + studioArea + "\""));
    }

    private String session(String title) {
        return alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", "{\"title\":\"" + title + "\"}").json("$.id");
    }

    private Response requestPlan(String sessionId) {
        Response requested = alice.post(content + "/sessions/" + sessionId + "/plan");
        assertThat(requested.status()).isEqualTo(200);
        Response done = awaitPlan(sessionId);
        assertThat(done.<String>json("$.plan.status")).isEqualTo("SUCCEEDED");
        return done;
    }

    private Response awaitPlan(String sessionId) {
        return Eventually.await(() -> alice.get(content + "/sessions/" + sessionId + "/plan"),
                r -> r.status() == 200 && !"PENDING".equals(r.json("$.plan.status")));
    }

    private void publishGallery(String sessionId, List<String> assetIds) {
        String galleries = "/api/workspaces/" + aliceWorkspace + "/galleries";
        Response gallery = alice.post(galleries, """
                {"sessionId":"%s","gallery":{"title":"Gallery","downloads":"NONE"}}""".formatted(sessionId));
        String galleryId = gallery.json("$.gallery.id");
        alice.put(galleries + "/" + galleryId + "/photos", "{\"version\":" + gallery.<Integer>json("$.gallery.version")
                + ",\"assetIds\":[\"" + String.join("\",\"", assetIds) + "\"]}");
        assertThat(alice.post(galleries + "/" + galleryId + "/status", "{\"status\":\"PUBLISHED\"}").status())
                .isEqualTo(200);
    }

    /** A ready photo of a 4:3 preview whose processing finished {@code minutesAgo}. */
    private String readyPhoto(String sessionId, int minutesAgo) {
        UUID id = UUID.randomUUID();
        String previewKey = "seed/preview/" + id;
        storage.upload(previewKey, TestImages.jpeg(64, 48), "image/jpeg");
        storage.upload("seed/original/" + id, TestImages.jpeg(64, 48), "image/jpeg");
        jdbc.update("""
                        INSERT INTO photo_assets (id, workspace_id, session_id, status, original_filename, content_type,
                                                  byte_size, original_key, thumbnail_key, preview_key, checksum_sha256,
                                                  width, height, processed_at, created_at)
                        VALUES (?, ?::uuid, ?::uuid, 'READY', 'photo.jpg', 'image/jpeg', 100, ?, ?, ?, ?, 4, 3,
                                now() - make_interval(mins => ?), now() - make_interval(mins => ?))
                        """, id, aliceWorkspace, sessionId, "seed/original/" + id, previewKey, previewKey,
                "0".repeat(64), minutesAgo, minutesAgo);
        return id.toString();
    }
}
