package com.photospace.jobs;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox relay: sends a queue message for every job that is due and has none. Several dispatchers (one per
 * API task) can run at once; {@code SKIP LOCKED} gives each its own rows. A message sent just before a crash
 * may be sent again, which workers tolerate.
 */
@Component
@ConditionalOnProperty("app.jobs.dispatcher.enabled")
class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JobStore store;
    private final JobQueue queue;
    private final JobProperties.Dispatcher settings;
    private final TransactionTemplate transaction;

    JobDispatcher(JobStore store, JobQueue queue, JobProperties properties, TransactionTemplate transaction) {
        this.store = store;
        this.queue = queue;
        this.settings = properties.dispatcher();
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${app.jobs.dispatcher.interval}")
    void dispatchDue() {
        try {
            Integer sent = transaction.execute(status -> {
                List<UUID> due = store.lockDueForDispatch(settings.batchSize(), settings.staleAfter());
                int count = 0;
                for (UUID jobId : due) {
                    try {
                        queue.send(jobId);
                        store.markDispatched(jobId);
                        count++;
                    } catch (RuntimeException e) {
                        // Left undispatched; the next run tries again.
                        log.warn("Could not dispatch job {}: {}", jobId, e.getClass().getSimpleName());
                    }
                }
                return count;
            });
            if (sent != null && sent > 0) {
                log.debug("Dispatched {} job(s)", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Job dispatch run failed: {}", e.getClass().getSimpleName());
        }
    }
}
