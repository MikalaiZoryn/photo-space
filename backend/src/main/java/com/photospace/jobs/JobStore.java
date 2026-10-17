package com.photospace.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SQL for job rows. Each state change is one conditional {@code UPDATE}, so two workers that receive the same
 * message cannot both claim the job, and a late result cannot overwrite a newer state.
 */
@Component
class JobStore {

    private static final String JOB_COLUMNS = "id, workspace_id, job_type, subject_id, attempts, max_attempts, correlation_id";
    private static final String VIEW_COLUMNS = """
            id, job_type, subject_id, status, attempts, max_attempts, last_error, created_at, updated_at, finished_at""";

    private final JdbcClient jdbc;

    JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a queued job unless one with the same idempotency key exists. Returns the ID of the job that now
     * holds the key.
     */
    UUID insert(UUID workspaceId, String type, UUID subjectId, String idempotencyKey, int maxAttempts,
            String correlationId) {
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO background_jobs
                               (workspace_id, job_type, subject_id, idempotency_key, status, max_attempts, correlation_id)
                        VALUES (:workspaceId, :type, :subjectId, :key, 'QUEUED', :maxAttempts, :correlationId)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("workspaceId", workspaceId)
                .param("type", type)
                .param("subjectId", subjectId)
                .param("key", idempotencyKey)
                .param("maxAttempts", maxAttempts)
                .param("correlationId", correlationId)
                .query(UUID.class)
                .optional();
        return inserted.orElseGet(() -> jdbc.sql("SELECT id FROM background_jobs WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(UUID.class)
                .single());
    }

    /**
     * Locks up to {@code limit} jobs that need a message: queued and due, and either never handed to the queue
     * or handed over more than {@code staleAfter} ago without running; or running on a lease that expired that
     * long ago. Must run in a transaction; rows locked by another dispatcher are skipped.
     */
    List<UUID> lockDueForDispatch(int limit, Duration staleAfter) {
        return jdbc.sql("""
                        SELECT id FROM background_jobs
                         WHERE status IN ('QUEUED', 'RUNNING') AND run_after <= now()
                           AND (dispatched_at IS NULL OR dispatched_at < now() - make_interval(secs => :stale))
                           AND (status = 'QUEUED' OR locked_until < now() - make_interval(secs => :stale))
                         ORDER BY run_after
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("stale", (double) staleAfter.toSeconds())
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    void markDispatched(UUID jobId) {
        jdbc.sql("UPDATE background_jobs SET dispatched_at = now(), updated_at = now() WHERE id = :id")
                .param("id", jobId)
                .update();
    }

    /**
     * Starts an attempt: a queued, due job, or a running one whose worker's lease expired. Empty if the job is
     * finished, not due, leased by another worker, or out of attempts.
     */
    Optional<Job> claim(UUID jobId, Duration lease) {
        return jdbc.sql("""
                        UPDATE background_jobs
                           SET status = 'RUNNING', attempts = attempts + 1,
                               locked_until = now() + make_interval(secs => :lease), updated_at = now()
                         WHERE id = :id AND attempts < max_attempts AND run_after <= now()
                           AND (status = 'QUEUED' OR (status = 'RUNNING' AND locked_until < now()))
                        RETURNING\s""" + JOB_COLUMNS)
                .param("id", jobId)
                .param("lease", (double) lease.toSeconds())
                .query(JobStore::job)
                .optional();
    }

    /**
     * Fails a job whose last attempt never reported back (its worker died) and that has no attempts left.
     * Returns the job so its handler can record the outcome, or empty if that does not apply.
     */
    Optional<Job> failIfExhausted(UUID jobId) {
        return jdbc.sql("""
                        UPDATE background_jobs
                           SET status = 'FAILED', locked_until = NULL, finished_at = now(), updated_at = now(),
                               last_error = coalesce(last_error, 'The worker stopped during the last attempt')
                         WHERE id = :id AND attempts >= max_attempts
                           AND (status = 'QUEUED' OR (status = 'RUNNING' AND locked_until < now()))
                        RETURNING\s""" + JOB_COLUMNS)
                .param("id", jobId)
                .query(JobStore::job)
                .optional();
    }

    void succeed(Job job) {
        jdbc.sql("""
                        UPDATE background_jobs
                           SET status = 'SUCCEEDED', locked_until = NULL, last_error = NULL,
                               finished_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                        """)
                .param("id", job.id())
                .param("attempt", job.attempt())
                .update();
    }

    /** Queues the next attempt after {@code delay}. The dispatcher sends a new message once it is due. */
    void retryLater(Job job, Duration delay, String safeError) {
        jdbc.sql("""
                        UPDATE background_jobs
                           SET status = 'QUEUED', locked_until = NULL, dispatched_at = NULL, last_error = :error,
                               run_after = now() + make_interval(secs => :delay), updated_at = now()
                         WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                        """)
                .param("id", job.id())
                .param("attempt", job.attempt())
                .param("delay", delay.toMillis() / 1000.0)
                .param("error", safeError)
                .update();
    }

    /** Returns false if the job was no longer this attempt's to fail. */
    boolean fail(Job job, String safeError) {
        return jdbc.sql("""
                        UPDATE background_jobs
                           SET status = 'FAILED', locked_until = NULL, last_error = :error,
                               finished_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                        """)
                .param("id", job.id())
                .param("attempt", job.attempt())
                .param("error", safeError)
                .update() == 1;
    }

    long count(UUID workspaceId, JobStatus status) {
        return jdbc.sql("SELECT count(*) FROM background_jobs WHERE workspace_id = :workspaceId"
                        + (status == null ? "" : " AND status = :status"))
                .param("workspaceId", workspaceId)
                .param("status", status == null ? null : status.name())
                .query(Long.class)
                .single();
    }

    List<JobView> list(UUID workspaceId, JobStatus status, int limit, long offset) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM background_jobs WHERE workspace_id = :workspaceId"
                        + (status == null ? "" : " AND status = :status")
                        + " ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset")
                .param("workspaceId", workspaceId)
                .param("status", status == null ? null : status.name())
                .param("limit", limit)
                .param("offset", offset)
                .query(JobStore::jobView)
                .list();
    }

    List<JobView> latestBySubject(UUID workspaceId, Collection<UUID> subjectIds) {
        return jdbc.sql("SELECT DISTINCT ON (subject_id) " + VIEW_COLUMNS + """
                         FROM background_jobs
                        WHERE workspace_id = :workspaceId AND subject_id IN (:ids)
                        ORDER BY subject_id, created_at DESC, id DESC
                        """)
                .param("workspaceId", workspaceId)
                .param("ids", subjectIds)
                .query(JobStore::jobView)
                .list();
    }

    private static JobView jobView(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new JobView(
                rs.getObject("id", UUID.class),
                rs.getString("job_type"),
                rs.getObject("subject_id", UUID.class),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                finished == null ? null : finished.toInstant());
    }

    private static Job job(ResultSet rs, int rowNum) throws SQLException {
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("job_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("correlation_id"));
    }
}
