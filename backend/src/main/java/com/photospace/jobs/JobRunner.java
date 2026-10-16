package com.photospace.jobs;

import com.photospace.shared.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs the job named by one queue message, at most once at a time per job, and records the outcome on the job
 * row. The caller deletes the message afterwards in every case: retries are scheduled on the row (and
 * re-dispatched when due), not by leaving messages on the queue.
 */
@Component
class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final Map<String, JobHandler> handlers;
    private final JobProperties properties;
    private final RandomGenerator random = RandomGenerator.getDefault();

    JobRunner(JobStore store, List<JobHandler> handlers, JobProperties properties) {
        this.store = store;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.properties = properties;
    }

    /** Runs the job if it can be claimed now. Never throws for a handler failure. */
    void run(UUID jobId) {
        Optional<Job> claimed = store.claim(jobId, properties.worker().lease());
        if (claimed.isEmpty()) {
            // Finished, not due yet, leased by another worker, or out of attempts after a crash.
            store.failIfExhausted(jobId).ifPresent(this::giveUp);
            return;
        }
        Job job = claimed.get();
        // Worker logs carry the correlation ID of the request that queued the job.
        MDC.put(CorrelationIdFilter.MDC_KEY, job.correlationId() != null ? job.correlationId() : "job-" + job.id());
        try {
            execute(job);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void execute(Job job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            log.error("Job {} has unknown type {}", job.id(), job.type());
            store.fail(job, "No handler for this job type");
            return;
        }
        try {
            handler.run(job);
            store.succeed(job);
            log.info("Job {} ({}) succeeded on attempt {}", job.id(), job.type(), job.attempt());
        } catch (Exception e) {
            String error = safeSummary(e);
            if (job.attempt() >= job.maxAttempts()) {
                log.warn("Job {} ({}) failed on its last attempt {}: {}", job.id(), job.type(), job.attempt(), error, e);
                if (store.fail(job, error)) {
                    giveUp(job);
                }
            } else {
                var delay = Backoff.delay(job.attempt(), properties.retry().baseDelay(), properties.retry().maxDelay(),
                        random);
                log.warn("Job {} ({}) failed on attempt {}, retrying in {}: {}", job.id(), job.type(), job.attempt(),
                        delay, error, e);
                store.retryLater(job, delay, error);
            }
        }
    }

    private void giveUp(Job job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            return;
        }
        try {
            handler.giveUp(job);
        } catch (RuntimeException e) {
            log.error("Recording the final failure of job {} ({}) failed", job.id(), job.type(), e);
        }
    }

    private static String safeSummary(Exception e) {
        String summary = e instanceof JobFailure ? e.getMessage() : e.getClass().getSimpleName();
        return summary.length() > 300 ? summary.substring(0, 300) : summary;
    }
}
