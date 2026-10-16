package com.photospace.assets;

import com.photospace.storage.ObjectStorage;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes uploads that were started but never completed: their object (if the browser uploaded one) and then
 * their row. The upload URL expired long before, so nothing can still complete them. Safe to run on several
 * instances at once: deleting a missing object succeeds, and a row is deleted only while still pending.
 */
@Component
@ConditionalOnProperty("app.assets.cleanup.enabled")
class AbandonedUploadCleanup {

    private static final Logger log = LoggerFactory.getLogger(AbandonedUploadCleanup.class);
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final AssetProperties.Cleanup settings;

    AbandonedUploadCleanup(JdbcClient jdbc, ObjectStorage storage, AssetProperties properties) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.settings = properties.cleanup();
    }

    @Scheduled(fixedDelayString = "${app.assets.cleanup.interval}", initialDelayString = "${app.assets.cleanup.interval}")
    void scheduledRun() {
        try {
            int removed = removeAbandoned();
            if (removed > 0) {
                log.info("Removed {} abandoned upload(s)", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Abandoned upload cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Removes up to one batch of abandoned uploads and returns how many. */
    int removeAbandoned() {
        List<Abandoned> abandoned = jdbc.sql("""
                        SELECT id, original_key FROM photo_assets
                         WHERE status = 'PENDING_UPLOAD' AND created_at < now() - make_interval(secs => :age)
                         ORDER BY created_at
                         LIMIT :limit
                        """)
                .param("age", (double) settings.abandonedAfter().toSeconds())
                .param("limit", BATCH)
                .query((rs, n) -> new Abandoned(rs.getObject("id", UUID.class), rs.getString("original_key")))
                .list();
        int removed = 0;
        for (Abandoned upload : abandoned) {
            // The object first: if deleting the row then fails, the next run retries both.
            storage.delete(upload.originalKey());
            removed += jdbc.sql("DELETE FROM photo_assets WHERE id = :id AND status = 'PENDING_UPLOAD'")
                    .param("id", upload.id())
                    .update();
        }
        return removed;
    }

    private record Abandoned(UUID id, String originalKey) {}
}
