package com.photospace.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.jobs.Job;
import com.photospace.storage.ObjectStorage;
import com.photospace.support.ApiClient;
import com.photospace.support.ApiClient.Response;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import com.photospace.support.TestImages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Photo uploads end to end: authorization of the upload, the presigned upload to the S3 emulator, completion,
 * asynchronous processing by the real worker, rejection of bad files, limits, cleanup of abandoned uploads, and
 * that no user can reach another workspace's sessions or assets.
 */
@IntegrationTest
class AssetsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private ProcessAssetJob processAssetJob;

    @Autowired
    private AbandonedUploadCleanup cleanup;

    private ApiClient alice;
    private ApiClient bob;
    private String aliceWorkspace;
    private String aliceAssets;
    private String aliceSessionAssets;
    private String aliceSessionId;
    private String bobWorkspace;

    @BeforeEach
    void setUp() {
        alice = new ApiClient(port).signIn("alice-" + UUID.randomUUID() + "@example.com");
        bob = new ApiClient(port).signIn("bob-" + UUID.randomUUID() + "@example.com");
        aliceWorkspace = alice.createWorkspace("Alice Studio", "Europe/Warsaw");
        aliceAssets = "/api/workspaces/" + aliceWorkspace + "/assets";
        aliceSessionId = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", "{\"title\":\"Family\"}")
                .json("$.id");
        aliceSessionAssets = "/api/workspaces/" + aliceWorkspace + "/sessions/" + aliceSessionId + "/assets";
        bobWorkspace = bob.createWorkspace("Bob Studio", "UTC");
    }

    @Test
    void uploadsAPhotoToPrivateStorageAndProcessesItAsynchronously() throws Exception {
        byte[] photo = TestImages.jpegWithOrientation(1200, 800, 6);

        Response started = startUpload(alice, aliceSessionAssets, "../IMG_0042.JPG", "image/jpeg", photo.length);

        assertThat(started.status()).isEqualTo(201);
        assertThat(started.<String>json("$.asset.status")).isEqualTo("PENDING_UPLOAD");
        assertThat(started.<String>json("$.asset.originalFilename")).isEqualTo("IMG_0042.JPG");
        assertThat(started.<String>json("$.asset.sessionId")).isEqualTo(aliceSessionId);
        assertThat(started.<Object>json("$.asset.thumbnailUrl")).isNull();
        assertThat(started.<String>json("$.upload.method")).isEqualTo("PUT");
        assertThat(started.<Map<String, String>>json("$.upload.headers")).containsEntry("content-type", "image/jpeg");
        assertThat(started.body()).as("no storage keys in the asset").doesNotContain("originalKey", "original_key");
        String assetId = started.json("$.asset.id");
        String key = jdbc.queryForObject("SELECT original_key FROM photo_assets WHERE id = ?::uuid", String.class, assetId);
        assertThat(key).isEqualTo("workspaces/" + aliceWorkspace + "/originals/" + assetId);
        assertThat(URI.create(started.json("$.upload.url")).getPath()).endsWith(key);

        assertThat(upload(started, photo).status()).isEqualTo(200);
        assertThat(storage.size(key)).contains((long) photo.length);

        Response completed = alice.post(aliceAssets + "/" + assetId + "/complete");
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.<String>json("$.status")).isIn("PROCESSING", "READY");
        assertThat(completed.<String>json("$.uploadedAt")).isNotNull();

        Response ready = awaitProcessed(assetId);
        assertThat(ready.<String>json("$.status")).isEqualTo("READY");
        assertThat(ready.<String>json("$.contentType")).isEqualTo("image/jpeg");
        assertThat(ready.<Integer>json("$.width")).as("upright: EXIF orientation 6").isEqualTo(800);
        assertThat(ready.<Integer>json("$.height")).isEqualTo(1200);
        assertThat(ready.<Integer>json("$.byteSize")).isEqualTo(photo.length);
        assertThat(ready.<String>json("$.checksumSha256")).isEqualTo(sha256(photo));
        assertThat(ready.<Object>json("$.failureReason")).isNull();

        HttpResponse<byte[]> thumbnail = ApiClient.getBytes(URI.create(ready.json("$.thumbnailUrl")));
        assertThat(thumbnail.statusCode()).isEqualTo(200);
        var thumbnailImage = ImageIO.read(new ByteArrayInputStream(thumbnail.body()));
        assertThat(thumbnailImage.getHeight()).isEqualTo(480);
        assertThat(thumbnailImage.getWidth()).isEqualTo(320);
        HttpResponse<byte[]> preview = ApiClient.getBytes(URI.create(ready.json("$.previewUrl")));
        assertThat(ImageIO.read(new ByteArrayInputStream(preview.body())).getHeight()).isEqualTo(1200);
        assertThat(ready.body()).as("the original is never linked").doesNotContain("/originals/");

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, attempts, correlation_id FROM background_jobs WHERE subject_id = ?::uuid", assetId);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempts")).isEqualTo(1);
        assertThat(job.get("correlation_id")).as("from the completing request").isEqualTo(completed.header("X-Correlation-Id"));

        Response list = alice.get(aliceSessionAssets);
        assertThat(list.<List<String>>json("$.items[*].id")).containsExactly(assetId);
        assertThat(list.<Integer>json("$.totalItems")).isEqualTo(1);
    }

    @Test
    void completingAgainChangesNothingAndQueuesNoSecondJob() {
        byte[] photo = TestImages.jpeg(300, 200);
        String assetId = uploadAndComplete(photo, "image/jpeg");
        awaitProcessed(assetId);

        Response again = alice.post(aliceAssets + "/" + assetId + "/complete");

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.<String>json("$.status")).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE subject_id = ?::uuid", Long.class,
                assetId)).isEqualTo(1);
    }

    @Test
    void reprocessingAfterACrashKeepsOneAssetWithTheSameDerivatives() throws Exception {
        String assetId = uploadAndComplete(TestImages.jpeg(300, 200), "image/jpeg");
        Response first = awaitProcessed(assetId);
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT thumbnail_key, preview_key, checksum_sha256 FROM photo_assets WHERE id = ?::uuid", assetId);

        // As if a worker uploaded the derivatives and died before recording the result: the job runs again.
        jdbc.update("UPDATE photo_assets SET status = 'PROCESSING' WHERE id = ?::uuid", assetId);
        Job retry = new Job(UUID.randomUUID(), UUID.fromString(aliceWorkspace), ProcessAssetJob.TYPE,
                UUID.fromString(assetId), 2, 3, null);
        processAssetJob.run(retry);
        processAssetJob.run(retry);

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT status, thumbnail_key, preview_key, checksum_sha256 FROM photo_assets WHERE id = ?::uuid", assetId);
        assertThat(after.get("status")).isEqualTo("READY");
        assertThat(after).containsAllEntriesOf(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_assets WHERE session_id = ?::uuid", Long.class,
                aliceSessionId)).isEqualTo(1);
        assertThat(first.<Integer>json("$.width")).isEqualTo(alice.get(aliceAssets + "/" + assetId).<Integer>json("$.width"));
    }

    @Test
    void detectsTheRealTypeFromTheFileContent() {
        // Declared as PNG, but the bytes are a JPEG: accepted, recorded as what it is.
        String assetId = uploadAndComplete(TestImages.jpeg(300, 200), "image/png");

        Response processed = awaitProcessed(assetId);

        assertThat(processed.<String>json("$.status")).isEqualTo("READY");
        assertThat(processed.<String>json("$.contentType")).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsAFileThatIsNotAPhotoAndDeletesTheOriginal() {
        byte[] script = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        String assetId = uploadAndComplete(script, "image/jpeg");

        Response rejected = awaitProcessed(assetId);

        assertThat(rejected.<String>json("$.status")).isEqualTo("REJECTED");
        assertThat(rejected.<String>json("$.failureReason")).isEqualTo(ImageProcessor.NOT_AN_IMAGE);
        assertThat(rejected.<Object>json("$.thumbnailUrl")).isNull();
        assertThat(storage.size(originalKey(assetId))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM background_jobs WHERE subject_id = ?::uuid", String.class,
                assetId)).as("a rejected file is an outcome, not a job failure").isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsAnUploadWhoseSizeDiffersFromTheDeclaredSize() {
        // S3 refuses this through the signed Content-Length; the emulator does not, so processing must.
        byte[] photo = TestImages.jpeg(300, 200);
        Response started = startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", photo.length + 10);
        String assetId = started.json("$.asset.id");
        upload(started, photo);
        alice.post(aliceAssets + "/" + assetId + "/complete");

        Response rejected = awaitProcessed(assetId);

        assertThat(rejected.<String>json("$.status")).isEqualTo("REJECTED");
        assertThat(rejected.<String>json("$.failureReason")).isEqualTo(ProcessAssetJob.SIZE_MISMATCH);
        assertThat(storage.size(originalKey(assetId))).isEmpty();
    }

    @Test
    void rejectsInvalidUploadRequests() {
        Response gif = startUpload(alice, aliceSessionAssets, "a.gif", "image/gif", 100);
        assertThat(gif.status()).isEqualTo(400);
        assertThat(gif.<String>json("$.errors.contentType")).isEqualTo("must be image/jpeg or image/png");

        Response tooBig = startUpload(alice, aliceSessionAssets, "a.jpg", "image/jpeg", 100L * 1024 * 1024 + 1);
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.<String>json("$.errors.byteSize")).isEqualTo("must be at most 100 MB");

        Response empty = startUpload(alice, aliceSessionAssets, "a.jpg", "image/jpeg", 0);
        assertThat(empty.<Map<String, String>>json("$.errors")).containsOnlyKeys("byteSize");

        Response unprintable = startUpload(alice, aliceSessionAssets, "photos/‮", "image/jpeg", 10);
        assertThat(unprintable.<String>json("$.errors.filename")).isEqualTo("must contain a printable name");

        Response missing = alice.post(aliceSessionAssets, "{}");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.<Map<String, String>>json("$.errors")).containsOnlyKeys("filename", "contentType", "byteSize");

        Response longName = startUpload(alice, aliceSessionAssets, "x".repeat(256), "image/jpeg", 10);
        assertThat(longName.<Map<String, String>>json("$.errors")).containsOnlyKeys("filename");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_assets WHERE session_id = ?::uuid", Long.class,
                aliceSessionId)).isZero();
    }

    @Test
    void completingBeforeTheFileIsUploadedIsAConflict() {
        Response started = startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 100);

        Response early = alice.post(aliceAssets + "/" + started.json("$.asset.id") + "/complete");

        assertThat(early.status()).isEqualTo(409);
        assertThat(early.<String>json("$.detail")).isEqualTo("The file has not been uploaded yet. Upload it, then try again.");
        assertThat(alice.get(aliceAssets + "/" + started.json("$.asset.id")).<String>json("$.status"))
                .isEqualTo("PENDING_UPLOAD");
    }

    @Test
    void refusesUploadsToAnArchivedSession() {
        alice.post("/api/workspaces/" + aliceWorkspace + "/sessions/" + aliceSessionId + "/archive");

        Response refused = startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 100);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.<String>json("$.detail")).contains("archived");
        assertThat(alice.get(aliceSessionAssets).status()).as("still listable").isEqualTo(200);
    }

    @Test
    void enforcesTheWorkspaceStorageQuota() {
        // A stored 100 GB minus 50 bytes: the next 51 bytes do not fit, 50 do.
        long quota = 100L * 1024 * 1024 * 1024;
        insertAsset(aliceWorkspace, aliceSessionId, "READY", quota - 50);

        Response over = startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 51);
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.<String>json("$.detail")).contains("storage limit");

        assertThat(startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 50).status()).isEqualTo(201);

        // Rejected and failed assets free their space.
        jdbc.update("UPDATE photo_assets SET status = 'FAILED', failure_reason = 'x' WHERE byte_size = ?", quota - 50);
        assertThat(startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 51).status()).isEqualTo(201);
    }

    @Test
    void limitsUploadsInProgressPerWorkspace() {
        for (int i = 0; i < 100; i++) {
            insertAsset(aliceWorkspace, aliceSessionId, "PENDING_UPLOAD", 10);
        }

        Response refused = startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 10);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.<String>json("$.detail")).contains("Too many uploads");
        assertThat(startUpload(bob, "/api/workspaces/" + bobWorkspace + "/sessions/" + bobSession() + "/assets",
                "photo.jpg", "image/jpeg", 10).status()).as("other workspaces are unaffected").isEqualTo(201);
    }

    @Test
    void membersCanUpload() {
        String bobUserId = bob.get("/api/me").json("$.id");
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'MEMBER')",
                aliceWorkspace, bobUserId);

        assertThat(startUpload(bob, aliceSessionAssets, "photo.jpg", "image/jpeg", 10).status()).isEqualTo(201);
    }

    @Test
    void otherWorkspacesCannotReachSessionsOrAssets() {
        Response started = startUpload(alice, aliceSessionAssets, "photo.jpg", "image/jpeg", 100);
        String assetId = started.json("$.asset.id");
        upload(started, new byte[100]);
        String bobAssets = "/api/workspaces/" + bobWorkspace + "/assets";
        String aliceSessionViaBob = "/api/workspaces/" + bobWorkspace + "/sessions/" + aliceSessionId + "/assets";

        // Through Alice's workspace: Bob is not a member.
        assertThat(bob.get(aliceSessionAssets).status()).isEqualTo(404);
        assertThat(startUpload(bob, aliceSessionAssets, "photo.jpg", "image/jpeg", 100).status()).isEqualTo(404);
        assertThat(bob.get(aliceAssets + "/" + assetId).status()).isEqualTo(404);
        assertThat(bob.post(aliceAssets + "/" + assetId + "/complete").status()).isEqualTo(404);
        // Through his own workspace, with Alice's IDs: the same as unknown IDs.
        assertThat(bob.get(aliceSessionViaBob).status()).isEqualTo(404);
        assertThat(startUpload(bob, aliceSessionViaBob, "photo.jpg", "image/jpeg", 100).status()).isEqualTo(404);
        assertThat(bob.get(bobAssets + "/" + assetId).status()).isEqualTo(404);
        assertThat(bob.post(bobAssets + "/" + assetId + "/complete").status()).isEqualTo(404);
        assertThat(bob.get(bobAssets + "/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(bob.get(bobAssets + "/not-a-uuid").status()).isEqualTo(404);

        // No side effects: still pending, no job, nothing created in Bob's workspace.
        assertThat(alice.get(aliceAssets + "/" + assetId).<String>json("$.status")).isEqualTo("PENDING_UPLOAD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE subject_id = ?::uuid", Long.class,
                assetId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_assets WHERE workspace_id = ?::uuid", Long.class,
                bobWorkspace)).isZero();
    }

    @Test
    void theDatabaseRefusesAnAssetInAnotherWorkspacesSession() {
        assertThatThrownBy(() -> insertAsset(bobWorkspace, aliceSessionId, "PENDING_UPLOAD", 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void removesAbandonedUploadsAndTheirObjects() {
        Response old = startUpload(alice, aliceSessionAssets, "old.jpg", "image/jpeg", 10);
        upload(old, new byte[10]);
        String oldId = old.json("$.asset.id");
        String recentId = startUpload(alice, aliceSessionAssets, "recent.jpg", "image/jpeg", 10).json("$.asset.id");
        String oldKey = originalKey(oldId);
        jdbc.update("UPDATE photo_assets SET created_at = now() - interval '25 hours' WHERE id = ?::uuid", oldId);

        assertThat(cleanup.removeAbandoned()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM photo_assets WHERE id = ?::uuid", Long.class, oldId)).isZero();
        assertThat(storage.size(oldKey)).isEmpty();
        assertThat(alice.get(aliceAssets + "/" + recentId).<String>json("$.status")).isEqualTo("PENDING_UPLOAD");
        assertThat(cleanup.removeAbandoned()).as("idempotent").isZero();
    }

    @Test
    void listsOnlyTheSessionsAssetsAndValidatesPaging() {
        uploadAndComplete(TestImages.jpeg(100, 100), "image/jpeg");
        String otherSession = alice.post("/api/workspaces/" + aliceWorkspace + "/sessions", "{\"title\":\"Other\"}")
                .json("$.id");
        startUpload(alice, "/api/workspaces/" + aliceWorkspace + "/sessions/" + otherSession + "/assets",
                "other.jpg", "image/jpeg", 10);

        assertThat(alice.get(aliceSessionAssets).<Integer>json("$.totalItems")).isEqualTo(1);
        assertThat(alice.get(aliceSessionAssets + "?size=101").status()).isEqualTo(400);
        assertThat(alice.get("/api/workspaces/" + aliceWorkspace + "/sessions/" + UUID.randomUUID() + "/assets")
                .status()).isEqualTo(404);
    }

    private String bobSession() {
        return bob.post("/api/workspaces/" + bobWorkspace + "/sessions", "{\"title\":\"Bob's\"}").json("$.id");
    }

    private String uploadAndComplete(byte[] content, String declaredType) {
        Response started = startUpload(alice, aliceSessionAssets, "photo", declaredType, content.length);
        assertThat(started.status()).isEqualTo(201);
        assertThat(upload(started, content).status()).isEqualTo(200);
        String assetId = started.json("$.asset.id");
        assertThat(alice.post(aliceAssets + "/" + assetId + "/complete").status()).isEqualTo(200);
        return assetId;
    }

    private Response awaitProcessed(String assetId) {
        return Eventually.await(() -> alice.get(aliceAssets + "/" + assetId),
                response -> !List.of("PENDING_UPLOAD", "PROCESSING").contains(response.<String>json("$.status")));
    }

    private static Response startUpload(ApiClient client, String path, String filename, String type, long size) {
        return client.post(path, "{\"filename\":\"%s\",\"contentType\":\"%s\",\"byteSize\":%d}"
                .formatted(filename.replace("\\", "\\\\").replace("\"", "\\\""), type, size));
    }

    /** Uploads like the browser: to the presigned URL, with exactly the headers the API returned. */
    private static Response upload(Response started, byte[] content) {
        return ApiClient.putBytes(URI.create(started.json("$.upload.url")), content, started.json("$.upload.headers"));
    }

    private void insertAsset(String workspaceId, String sessionId, String status, long size) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO photo_assets (id, workspace_id, session_id, status, original_filename, content_type,
                                                  byte_size, original_key, thumbnail_key, preview_key, checksum_sha256,
                                                  width, height)
                        VALUES (?, ?::uuid, ?::uuid, ?, 'seed.jpg', 'image/jpeg', ?, ?, 't', 'p', ?, 1, 1)
                        """, id, workspaceId, sessionId, status, size, "seed/" + id, "0".repeat(64));
    }

    private String originalKey(String assetId) {
        return jdbc.queryForObject("SELECT original_key FROM photo_assets WHERE id = ?::uuid", String.class, assetId);
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException, IOException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
