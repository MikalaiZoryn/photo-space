package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.storage.ObjectStorage;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import com.photospace.support.TestImages;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The content studio end to end, with the real job worker and the scriptable mock provider: what a batch sends
 * to the provider, drafts that persist, versioned edits, approval of an exact version, retries of temporary
 * failures, permanent failures and manual retry, request validation, and workspace isolation.
 */
@IntegrationTest
class ContentIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectStorage storage;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String aliceSession;
    private String content;
    private String bobWorkspace;
    private String bobSession;

    @BeforeEach
    void setUp() {
        TestContentProvider.FAILURES.clear();
        TestContentProvider.REQUESTS.clear();
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        String client = alice.post("/api/workspaces/" + aliceWorkspace + "/clients",
                "{\"name\":\"Anna Nowak\",\"email\":\"anna.nowak@example.com\",\"phone\":\"+48 600 700 800\"}").json("$.id");
        aliceSession = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", """
                {"title":"Anna Nowak family","sessionType":"Family","clientId":"%s","location":"Private garden",
                 "notes":"Session notes that are not sent unless copied"}""".formatted(client)).json("$.id");
        content = "/api/workspaces/" + aliceWorkspace + "/content";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
        bobSession = bob.post("/api/workspaces/" + bobWorkspace + "/sessions", "{\"title\":\"Bob's\"}").json("$.id");
    }

    @Test
    void generatesDraftsFromTheSelectedPhotosAndNotes() {
        byte[] firstPreview = TestImages.jpeg(64, 48);
        byte[] secondPreview = TestImages.jpeg(48, 64);
        String first = readyPhoto(aliceWorkspace, aliceSession, firstPreview);
        String second = readyPhoto(aliceWorkspace, aliceSession, secondPreview);
        assertThat(alice.put(content + "/brand-profile", """
                {"version":0,"profile":{"voice":" Warm and calm ","audience":"Young families","preferences":"",
                 "avoid":"clichés"}}""").<Integer>json("$.version")).isEqualTo(1);

        Response created = alice.post(content + "/batches", batchJson(aliceSession, List.of(second, first),
                "Picnic by the lake. The kids flew kites.", 3));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.<String>json("$.batch.status")).isEqualTo("PENDING");
        assertThat(created.<List<String>>json("$.photos[*].id")).containsExactly(second, first);
        String batchId = created.json("$.batch.id");

        Response done = awaitFinished(batchId);
        assertThat(done.<String>json("$.batch.status")).isEqualTo("SUCCEEDED");
        assertThat(done.<String>json("$.batch.provider")).isEqualTo(MockContentProvider.PROVIDER);
        assertThat(done.<String>json("$.batch.model")).isEqualTo(MockContentProvider.MODEL);
        assertThat(done.<String>json("$.batch.promptVersion")).isEqualTo(ContentPrompt.VERSION);
        assertThat(done.<String>json("$.batch.sessionTitle")).isEqualTo("Anna Nowak family");
        assertThat(done.<Integer>json("$.batch.draftCount")).isEqualTo(3);
        assertThat(done.<String>json("$.batch.completedAt")).isNotNull();
        assertThat(done.<List<String>>json("$.carousel")).containsExactly(second, first);
        assertThat(done.<List<String>>json("$.batch.storyIdeas")).hasSize(2);
        List<String> captions = done.json("$.drafts[*].caption");
        assertThat(captions).hasSize(3).allSatisfy(c -> assertThat(c).contains("Picnic by the lake."));
        assertThat(done.<List<String>>json("$.drafts[*].status")).containsOnly("DRAFT");
        assertThat(done.<List<Integer>>json("$.drafts[*].version")).containsOnly(1);
        assertThat(done.<List<String>>json("$.drafts[*].source")).containsOnly("GENERATED");
        assertThat(done.<List<String>>json("$.drafts[0].hashtags")).contains("familyphotography");
        assertThat(done.<String>json("$.drafts[0].callToAction")).isNotBlank();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM content_draft_versions v JOIN content_drafts d ON d.id = v.draft_id
                 WHERE d.batch_id = ?::uuid AND v.created_by IS NULL""", Integer.class, batchId)).isEqualTo(3);

        // Exactly what the provider received: the previews in selection order, the brand profile, the session
        // type, and the notes sent with the request. No client details, session title, location, or session notes.
        assertThat(TestContentProvider.REQUESTS).hasSize(1);
        GenerationRequest sent = TestContentProvider.REQUESTS.getFirst();
        assertThat(sent.photos()).extracting(GenerationRequest.Photo::ref).containsExactly("photo-1", "photo-2");
        assertThat(sent.photos().get(0).jpeg()).isEqualTo(secondPreview);
        assertThat(sent.photos().get(1).jpeg()).isEqualTo(firstPreview);
        assertThat(sent.brand()).isEqualTo(new GenerationRequest.Brand("Warm and calm", "Young families", null, "clichés"));
        assertThat(sent.sessionType()).isEqualTo("Family");
        assertThat(sent.notes()).isEqualTo("Picnic by the lake. The kids flew kites.");
        assertThat(sent.captionCount()).isEqualTo(3);
        String everyText = String.join("|", sent.brand().toString(), sent.sessionType(), sent.notes());
        assertThat(everyText).doesNotContain("Anna", "Nowak", "anna.nowak@example.com", "600 700 800",
                "Private garden", "Session notes");

        // Drafts persist: a fresh read, as after a reload, and the list.
        assertThat(alice.get(content + "/batches/" + batchId).<List<String>>json("$.drafts[*].caption"))
                .isEqualTo(captions);
        Response list = alice.get(content + "/batches?sessionId=" + aliceSession);
        assertThat(list.<List<String>>json("$.items[*].id")).containsExactly(batchId);
        assertThat(list.<Integer>json("$.items[0].photoCount")).isEqualTo(2);
        assertThat(alice.get(content + "/batches?sessionId=" + UUID.randomUUID()).<Integer>json("$.totalItems")).isZero();
    }

    @Test
    void editsAreVersionedAndApprovalAppliesToOneVersion() {
        String draft = firstDraft(generated(1));
        String drafts = content + "/drafts/" + draft;

        Response edited = editDraft(draft, 1, "Kites over the lake.", "Message me to book.", "\"#Lake\",\"lake\",\"kids_2026\"");
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.<Integer>json("$.version")).isEqualTo(2);
        assertThat(edited.<String>json("$.source")).isEqualTo("EDITED");
        assertThat(edited.<List<String>>json("$.hashtags")).containsExactly("Lake", "kids_2026");
        assertThat(edited.<String>json("$.caption")).isEqualTo("Kites over the lake.");

        assertThat(editDraft(draft, 1, "Stale edit", null, "").status()).as("stale version").isEqualTo(409);
        assertThat(setStatus(draft, 1, "APPROVED").status()).as("approving text not reviewed").isEqualTo(409);

        Response approved = setStatus(draft, 2, "APPROVED");
        assertThat(approved.<String>json("$.status")).isEqualTo("APPROVED");
        assertThat(approved.<Integer>json("$.approvedVersion")).isEqualTo(2);
        assertThat(approved.<String>json("$.approvedAt")).isNotNull();
        assertThat(setStatus(draft, 2, "APPROVED").<Integer>json("$.approvedVersion")).as("idempotent").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT approved_by FROM content_drafts WHERE id = ?::uuid", UUID.class, draft))
                .isNotNull();

        Response same = editDraft(draft, 2, "Kites over the lake.", "Message me to book.", "\"Lake\",\"kids_2026\"");
        assertThat(same.<Integer>json("$.version")).as("saving the same text changes nothing").isEqualTo(2);
        assertThat(same.<String>json("$.status")).isEqualTo("APPROVED");

        Response reopened = editDraft(draft, 2, "Kites over the lake at sunset.", null, "");
        assertThat(reopened.<Integer>json("$.version")).isEqualTo(3);
        assertThat(reopened.<String>json("$.status")).as("an edit needs a new approval").isEqualTo("DRAFT");
        assertThat(reopened.<Integer>json("$.approvedVersion")).isNull();
        assertThat(reopened.<String>json("$.callToAction")).isNull();

        assertThat(setStatus(draft, 3, "APPROVED").<String>json("$.status")).isEqualTo("APPROVED");
        assertThat(setStatus(draft, 3, "DRAFT").<Integer>json("$.approvedVersion")).isNull();
        assertThat(jdbc.queryForList("""
                        SELECT action || ' ' || detail FROM audit_events
                         WHERE target_id = ?::uuid AND actor_id IS NOT NULL ORDER BY created_at, id""",
                String.class, draft))
                .as("each approval and withdrawal is audited with its version, the idempotent repeat is not")
                .containsExactly("CONTENT_DRAFT_APPROVED version 2", "CONTENT_DRAFT_APPROVED version 3",
                        "CONTENT_DRAFT_UNAPPROVED version 3");

        Response history = alice.get(drafts);
        assertThat(history.<List<Integer>>json("$.versions[*].version")).containsExactly(3, 2, 1);
        assertThat(history.<List<String>>json("$.versions[*].source")).containsExactly("EDITED", "EDITED", "GENERATED");
        assertThat(history.<String>json("$.versions[1].caption")).isEqualTo("Kites over the lake.");

        assertThat(editDraft(draft, 3, "Fine", null, "\"two words\"").<String>json("$.errors.hashtags"))
                .isEqualTo("must be letters, digits, or underscores, without spaces");
        assertThat(editDraft(draft, 3, " ", null, "").<String>json("$.errors['draft.caption']")).isNotNull();
        assertThat(editDraft(draft, 3, "a".repeat(2190), "Book now.", "").<String>json("$.errors.caption"))
                .contains("at most 2200");
        assertThat(alice.put(drafts, "{\"draft\":{\"caption\":\"x\",\"hashtags\":[]}}").status()).isEqualTo(400);
        assertThat(alice.get(drafts).<Integer>json("$.draft.version")).as("rejected edits saved nothing").isEqualTo(3);
    }

    @Test
    void flagsDetailsThatAreNotInTheNotesOrBrandProfile() {
        String draft = firstDraft(generated(1));

        Response edited = editDraft(draft, 1, "Thanks @anna.nowak for a lovely afternoon!", null, "");
        assertThat(edited.<List<String>>json("$.checks")).singleElement().asString().contains("@anna.nowak");

        alice.put(content + "/brand-profile", "{\"version\":0,\"profile\":{\"voice\":\"Tag @anna.nowak when asked\"}}");
        assertThat(alice.get(content + "/drafts/" + draft).<List<String>>json("$.draft.checks")).isEmpty();
    }

    @Test
    void retriesTemporaryFailuresAndReportsPermanentOnes() {
        String photo = readyPhoto(aliceWorkspace, aliceSession, TestImages.jpeg(32, 32));

        TestContentProvider.FAILURES.add(Kind.RATE_LIMITED);
        String retried = alice.post(content + "/batches", batchJson(aliceSession, List.of(photo), "One", 1)).json("$.batch.id");
        assertThat(awaitFinished(retried).<String>json("$.batch.status")).isEqualTo("SUCCEEDED");
        assertThat(TestContentProvider.REQUESTS).as("one failed and one successful call").hasSize(2);
        assertThat(alice.get(content + "/batches/" + retried).<String>json("$.batch.failureReason")).isNull();

        TestContentProvider.FAILURES.add(Kind.NOT_CONFIGURED);
        String failed = alice.post(content + "/batches", batchJson(aliceSession, List.of(photo), "Two", 1)).json("$.batch.id");
        Response failure = awaitFinished(failed);
        assertThat(failure.<String>json("$.batch.status")).isEqualTo("FAILED");
        assertThat(failure.<String>json("$.batch.failureReason")).isEqualTo(Kind.NOT_CONFIGURED.userMessage());
        assertThat(failure.<List<Object>>json("$.drafts")).isEmpty();
        assertThat(TestContentProvider.REQUESTS).as("a permanent failure is not retried").hasSize(3);

        Response again = alice.post(content + "/batches/" + failed + "/retry");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.<String>json("$.batch.status")).isEqualTo("PENDING");
        assertThat(again.<Integer>json("$.batch.run")).isEqualTo(2);
        assertThat(again.<String>json("$.batch.failureReason")).isNull();
        Response recovered = awaitFinished(failed);
        assertThat(recovered.<String>json("$.batch.status")).isEqualTo("SUCCEEDED");
        assertThat(recovered.<List<Object>>json("$.drafts")).hasSize(1);
        assertThat(alice.post(content + "/batches/" + failed + "/retry").status()).isEqualTo(409);

        for (int i = 0; i < 3; i++) {
            TestContentProvider.FAILURES.add(Kind.UNAVAILABLE);
        }
        String exhausted = alice.post(content + "/batches", batchJson(aliceSession, List.of(photo), "Three", 1)).json("$.batch.id");
        Response gaveUp = awaitFinished(exhausted);
        assertThat(gaveUp.<String>json("$.batch.status")).isEqualTo("FAILED");
        assertThat(gaveUp.<String>json("$.batch.failureReason")).isEqualTo(Kind.UNAVAILABLE.userMessage());
        assertThat(jdbc.queryForObject("""
                SELECT status || ':' || attempts || ':' || last_error FROM background_jobs
                 WHERE subject_id = ?::uuid""", String.class, exhausted))
                .isEqualTo("FAILED:3:" + Kind.UNAVAILABLE.userMessage());
    }

    @Test
    void regeneratesWithTheSameInputAsANewBatch() {
        String batchId = generated(2);
        Response original = alice.get(content + "/batches/" + batchId);

        Response regenerated = alice.post(content + "/batches/" + batchId + "/regenerate");
        assertThat(regenerated.status()).isEqualTo(201);
        String newId = regenerated.json("$.batch.id");
        assertThat(newId).isNotEqualTo(batchId);
        assertThat(regenerated.<List<String>>json("$.photos[*].id")).isEqualTo(original.json("$.photos[*].id"));
        assertThat(regenerated.<String>json("$.batch.notes")).isEqualTo(original.json("$.batch.notes"));
        assertThat(regenerated.<Integer>json("$.batch.captionCount")).isEqualTo(2);
        assertThat(awaitFinished(newId).<List<Object>>json("$.drafts")).hasSize(2);
        assertThat(alice.get(content + "/batches/" + batchId).<List<String>>json("$.drafts[*].id"))
                .isEqualTo(original.json("$.drafts[*].id"));
        assertThat(alice.get(content + "/batches").<Integer>json("$.totalItems")).isEqualTo(2);
    }

    @Test
    void validatesTheRequest() {
        String photo = readyPhoto(aliceWorkspace, aliceSession, TestImages.jpeg(8, 8));
        String otherSession = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", "{\"title\":\"Other\"}").json("$.id");
        String otherSessionPhoto = readyPhoto(aliceWorkspace, otherSession, TestImages.jpeg(8, 8));
        String processing = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO photo_assets (id, workspace_id, session_id, status, original_filename, content_type, byte_size,
                                          original_key)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'PROCESSING', 'p.jpg', 'image/jpeg', 1, 'seed/' || ?)""",
                processing, aliceWorkspace, aliceSession, processing);
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(readyPhoto(aliceWorkspace, aliceSession, TestImages.jpeg(4, 4)));
        }

        assertThat(fieldError(batchJson(aliceSession, List.of(), null, 1), "assetIds")).contains("at least one");
        assertThat(fieldError(batchJson(aliceSession, List.of(photo, photo), null, 1), "assetIds")).contains("twice");
        assertThat(fieldError(batchJson(aliceSession, eleven, null, 1), "assetIds")).contains("at most 10");
        assertThat(fieldError(batchJson(aliceSession, List.of(otherSessionPhoto), null, 1), "assetIds")).contains("ready photos");
        assertThat(fieldError(batchJson(aliceSession, List.of(processing), null, 1), "assetIds")).contains("ready photos");
        assertThat(fieldError(batchJson(UUID.randomUUID().toString(), List.of(photo), null, 1), "sessionId")).isNotNull();
        assertThat(fieldError(batchJson(aliceSession, List.of(photo), null, 0), "captionCount")).isNotNull();
        assertThat(fieldError(batchJson(aliceSession, List.of(photo), null, 6), "captionCount")).isNotNull();
        assertThat(fieldError(batchJson(aliceSession, List.of(photo), "n".repeat(4001), 1), "notes")).isNotNull();
        assertThat(alice.post(content + "/batches", "{\"sessionId\":\"%s\",\"assetIds\":[\"%s\"],\"captionCount\":1}"
                .formatted(aliceSession, photo)).<String>json("$.errors.includeHashtags")).isNotNull();

        alice.post("/api/workspaces/" + aliceWorkspace + "/sessions/" + aliceSession + "/archive");
        assertThat(alice.post(content + "/batches", batchJson(aliceSession, List.of(photo), null, 1)).status()).isEqualTo(409);
        assertThat(alice.get(content + "/batches").<Integer>json("$.totalItems")).as("nothing was created").isZero();
        assertThat(alice.get(content + "/batches?page=-1").status()).isEqualTo(400);
    }

    @Test
    void keepsTheBrandProfileWithOptimisticLocking() {
        Response empty = alice.get(content + "/brand-profile");
        assertThat(empty.<Integer>json("$.version")).isZero();
        assertThat(empty.<String>json("$.voice")).isNull();
        assertThat(empty.<String>json("$.updatedAt")).isNull();

        Response saved = alice.put(content + "/brand-profile", "{\"version\":0,\"profile\":{\"voice\":\"Calm\",\"avoid\":\"  \"}}");
        assertThat(saved.<Integer>json("$.version")).isEqualTo(1);
        assertThat(saved.<String>json("$.avoid")).isNull();
        assertThat(alice.put(content + "/brand-profile", "{\"version\":0,\"profile\":{\"voice\":\"Lost\"}}").status())
                .isEqualTo(409);
        assertThat(alice.put(content + "/brand-profile", "{\"version\":1,\"profile\":{\"voice\":\"%s\"}}"
                .formatted("v".repeat(1001))).<String>json("$.errors['profile.voice']")).isNotNull();
        assertThat(alice.get(content + "/brand-profile").<String>json("$.voice")).isEqualTo("Calm");

        Response settings = alice.get(content + "/settings");
        assertThat(settings.<String>json("$.provider")).isEqualTo("mock");
        assertThat(settings.<Integer>json("$.maxPhotos")).isEqualTo(10);
    }

    @Test
    void otherWorkspacesCannotReachContent() {
        String batchId = generated(1);
        String draft = firstDraft(batchId);
        String bobContent = "/api/workspaces/" + bobWorkspace + "/content";
        String alicePhoto = alice.get(content + "/batches/" + batchId).json("$.photos[0].id");
        alice.put(content + "/brand-profile", "{\"version\":0,\"profile\":{\"voice\":\"Alice's secret voice\"}}");

        for (String base : List.of(content, bobContent)) {
            assertThat(bob.get(base + "/batches/" + batchId).status()).isEqualTo(404);
            assertThat(bob.post(base + "/batches/" + batchId + "/retry").status()).isEqualTo(404);
            assertThat(bob.post(base + "/batches/" + batchId + "/regenerate").status()).isEqualTo(404);
            assertThat(bob.get(base + "/drafts/" + draft).status()).isEqualTo(404);
            assertThat(bob.put(base + "/drafts/" + draft, "{\"version\":1,\"draft\":{\"caption\":\"Hijacked\",\"hashtags\":[]}}")
                    .status()).isEqualTo(404);
            assertThat(bob.post(base + "/drafts/" + draft + "/status", "{\"version\":1,\"status\":\"APPROVED\"}").status())
                    .isEqualTo(404);
        }
        assertThat(bob.get(content + "/batches").status()).isEqualTo(404);
        assertThat(bob.get(content + "/brand-profile").status()).isEqualTo(404);
        assertThat(bob.put(content + "/brand-profile", "{\"version\":1,\"profile\":{}}").status()).isEqualTo(404);
        assertThat(bob.get(content + "/settings").status()).isEqualTo(404);
        assertThat(bob.post(content + "/batches", batchJson(aliceSession, List.of(alicePhoto), null, 1)).status())
                .isEqualTo(404);

        assertThat(bob.get(bobContent + "/batches").<Integer>json("$.totalItems")).isZero();
        assertThat(bob.get(bobContent + "/brand-profile").<String>json("$.voice")).isNull();
        // Bob's own workspace path with Alice's session or photos: they don't exist there.
        assertThat(bob.post(bobContent + "/batches", batchJson(aliceSession, List.of(alicePhoto), null, 1))
                .<String>json("$.errors.sessionId")).isNotNull();
        assertThat(bob.post(bobContent + "/batches", batchJson(bobSession, List.of(alicePhoto), null, 1))
                .<String>json("$.errors.assetIds")).isNotNull();

        Response unchanged = alice.get(content + "/drafts/" + draft);
        assertThat(unchanged.<Integer>json("$.draft.version")).isEqualTo(1);
        assertThat(unchanged.<String>json("$.draft.status")).isEqualTo("DRAFT");
        assertThat(alice.get(content + "/batches").<Integer>json("$.totalItems")).as("no batch was added").isEqualTo(1);
    }

    // --- Helpers. ---

    /** A succeeded batch of alice's session with one photo and {@code captions} drafts. */
    private String generated(int captions) {
        String photo = readyPhoto(aliceWorkspace, aliceSession, TestImages.jpeg(16, 16));
        String batchId = alice.post(content + "/batches", batchJson(aliceSession, List.of(photo), "Kites.", captions))
                .json("$.batch.id");
        assertThat(awaitFinished(batchId).<String>json("$.batch.status")).isEqualTo("SUCCEEDED");
        return batchId;
    }

    private String firstDraft(String batchId) {
        return alice.get(content + "/batches/" + batchId).json("$.drafts[0].id");
    }

    private Response awaitFinished(String batchId) {
        return Eventually.await(() -> alice.get(content + "/batches/" + batchId),
                response -> !"PENDING".equals(response.json("$.batch.status")));
    }

    private Response editDraft(String draftId, int version, String caption, String callToAction, String hashtags) {
        return alice.put(content + "/drafts/" + draftId, """
                {"version":%d,"draft":{"caption":"%s","callToAction":%s,"hashtags":[%s]}}"""
                .formatted(version, caption, callToAction == null ? "null" : "\"" + callToAction + "\"", hashtags));
    }

    private Response setStatus(String draftId, int version, String status) {
        return alice.post(content + "/drafts/" + draftId + "/status",
                "{\"version\":%d,\"status\":\"%s\"}".formatted(version, status));
    }

    private String fieldError(String json, String field) {
        Response response = alice.post(content + "/batches", json);
        assertThat(response.status()).isEqualTo(400);
        return response.json("$.errors." + field);
    }

    private static String batchJson(String sessionId, List<String> assetIds, String notes, int captionCount) {
        String ids = assetIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return """
                {"sessionId":"%s","assetIds":[%s],"notes":%s,"captionCount":%d,"includeHashtags":true,
                 "includeCallToAction":true}"""
                .formatted(sessionId, ids, notes == null ? "null" : "\"" + notes + "\"", captionCount);
    }

    /** A ready photo whose preview exists in storage (the original and thumbnail are never read here). */
    private String readyPhoto(String workspaceId, String sessionId, byte[] preview) {
        UUID id = UUID.randomUUID();
        String previewKey = "seed/preview/" + id;
        storage.upload(previewKey, preview, "image/jpeg");
        jdbc.update("""
                        INSERT INTO photo_assets (id, workspace_id, session_id, status, original_filename, content_type,
                                                  byte_size, original_key, thumbnail_key, preview_key, checksum_sha256,
                                                  width, height)
                        VALUES (?, ?::uuid, ?::uuid, 'READY', 'photo.jpg', 'image/jpeg', 100, ?, ?, ?, ?, 4, 3)
                        """, id, workspaceId, sessionId, "seed/original/" + id, "seed/thumbnail/" + id, previewKey,
                "0".repeat(64));
        return id.toString();
    }
}
