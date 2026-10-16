package com.photospace.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.support.ApiClient;
import com.photospace.support.Eventually;
import com.photospace.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job system end to end, through the real dispatcher, SQS emulator, and worker: the outbox, retries with
 * backoff, giving up after the last attempt, idempotent enqueueing, duplicate messages, and recovery of jobs
 * whose worker died. Uses the test-only {@link TestJobHandler} (3 attempts; see {@link IntegrationTest}).
 */
@IntegrationTest
class JobsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JobService jobs;

    @Autowired
    private JobQueue queue;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        ApiClient owner = new ApiClient(port).signIn("jobs-" + UUID.randomUUID() + "@example.com");
        workspaceId = UUID.fromString(owner.createWorkspace("Jobs Studio", "UTC"));
    }

    @Test
    void runsAQueuedJobOnceTheEnqueueingTransactionCommits() {
        UUID subject = UUID.randomUUID();
        UUID jobId = enqueue(subject, "test:" + subject);

        Map<String, Object> job = awaitFinished(jobId);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempts")).isEqualTo(1);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(job.get("dispatched_at")).isNotNull();
        assertThat(TestJobHandler.RUNS.get(subject)).hasValue(1);
    }

    @Test
    void retriesWithBackoffUntilTheJobSucceeds() {
        UUID subject = UUID.randomUUID();
        TestJobHandler.FAILURES_BEFORE_SUCCESS.put(subject, 2);

        Map<String, Object> job = awaitFinished(enqueue(subject, "test:" + subject));

        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempts")).isEqualTo(3);
        assertThat(job.get("last_error")).as("cleared on success").isNull();
        assertThat(TestJobHandler.RUNS.get(subject)).hasValue(3);
        assertThat(TestJobHandler.GAVE_UP).doesNotContain(subject);
    }

    @Test
    void givesUpAfterTheLastAttemptWithASafeErrorSummary() {
        UUID subject = UUID.randomUUID();
        TestJobHandler.FAILURES_BEFORE_SUCCESS.put(subject, 100);

        Map<String, Object> job = awaitFinished(enqueue(subject, "test:" + subject));

        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("attempts")).isEqualTo(3);
        assertThat(job.get("last_error")).as("the class name, never the message").isEqualTo("IllegalStateException");
        assertThat(TestJobHandler.RUNS.get(subject)).hasValue(3);
        Eventually.await(() -> TestJobHandler.GAVE_UP.contains(subject), Boolean::booleanValue);
    }

    @Test
    void enqueueingTheSameKeyTwiceKeepsOneJob() {
        UUID subject = UUID.randomUUID();
        String key = "test:" + subject;

        UUID first = enqueue(subject, key);
        UUID second = enqueue(subject, key);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1);
        awaitFinished(first);
        assertThat(TestJobHandler.RUNS.get(subject)).hasValue(1);
    }

    @Test
    void enqueueingNeedsTheCallersTransactionAndRollsBackWithIt() {
        UUID subject = UUID.randomUUID();
        assertThatThrownBy(() -> jobs.enqueue(workspaceId, TestJobHandler.TYPE, subject, "test:" + subject))
                .isInstanceOf(IllegalTransactionStateException.class);

        transaction.executeWithoutResult(status -> {
            jobs.enqueue(workspaceId, TestJobHandler.TYPE, subject, "test:" + subject);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE subject_id = ?", Long.class, subject))
                .isZero();
    }

    @Test
    void duplicateMessagesDoNotRunAFinishedJobAgain() {
        UUID subject = UUID.randomUUID();
        UUID jobId = enqueue(subject, "test:" + subject);
        awaitFinished(jobId);

        queue.send(jobId);
        queue.send(jobId);
        // A later job finishing shows the worker has polled past the duplicates (one worker thread).
        UUID probe = UUID.randomUUID();
        awaitFinished(enqueue(probe, "test:" + probe));
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(TestJobHandler.RUNS.get(subject)).hasValue(1);
        assertThat(job(jobId).get("attempts")).isEqualTo(1);
    }

    @Test
    void retriesAJobWhoseWorkerDiedMidAttempt() {
        UUID subject = UUID.randomUUID();
        // Claimed an hour ago by a worker that never reported back; its lease ran out.
        UUID jobId = insertRunningJob(subject, 1);

        Map<String, Object> job = awaitFinished(jobId);

        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempts")).isEqualTo(2);
        assertThat(TestJobHandler.RUNS.get(subject)).hasValue(1);
    }

    @Test
    void failsAJobWhoseWorkerDiedDuringTheLastAttempt() {
        UUID subject = UUID.randomUUID();
        UUID jobId = insertRunningJob(subject, 3);

        Map<String, Object> job = awaitFinished(jobId);

        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("last_error")).isEqualTo("The worker stopped during the last attempt");
        assertThat(TestJobHandler.RUNS).doesNotContainKey(subject);
        Eventually.await(() -> TestJobHandler.GAVE_UP.contains(subject), Boolean::booleanValue);
    }

    @Test
    void recordsTheCorrelationIdOfTheRequestThatQueuedTheJob() {
        // Outside a request there is none; asset uploads (AssetsIT) record the request's ID.
        UUID subject = UUID.randomUUID();
        assertThat(job(enqueue(subject, "test:" + subject)).get("correlation_id")).isNull();
    }

    private UUID enqueue(UUID subject, String key) {
        return transaction.execute(status -> jobs.enqueue(workspaceId, TestJobHandler.TYPE, subject, key));
    }

    private UUID insertRunningJob(UUID subject, int attempts) {
        return jdbc.queryForObject("""
                        INSERT INTO background_jobs (workspace_id, job_type, subject_id, idempotency_key, status,
                                                     attempts, max_attempts, dispatched_at, locked_until)
                        VALUES (?, ?, ?, ?, 'RUNNING', ?, 3, now() - interval '1 hour', now() - interval '30 minutes')
                        RETURNING id
                        """, UUID.class, workspaceId, TestJobHandler.TYPE, subject, "test:" + subject, attempts);
    }

    private Map<String, Object> awaitFinished(UUID jobId) {
        return Eventually.await(() -> job(jobId),
                job -> job.get("status").equals("SUCCEEDED") || job.get("status").equals("FAILED"));
    }

    private Map<String, Object> job(UUID jobId) {
        return jdbc.queryForMap("SELECT * FROM background_jobs WHERE id = ?", jobId);
    }
}
