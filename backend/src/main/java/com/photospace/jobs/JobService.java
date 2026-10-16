package com.photospace.jobs;

import com.photospace.shared.web.CorrelationIdFilter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules use to start background work. {@link #enqueue} must run inside the caller's transaction:
 * the job row commits or rolls back together with the change that needs it (a transactional outbox), and the
 * dispatcher sends the queue message only after that commit.
 */
@Service
public class JobService {

    private final JobStore store;
    private final JobProperties properties;

    JobService(JobStore store, JobProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Queues a job for {@code subjectId} unless a job with {@code idempotencyKey} already exists, in which case
     * that job's ID is returned and nothing changes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(UUID workspaceId, String type, UUID subjectId, String idempotencyKey) {
        return store.insert(workspaceId, type, subjectId, idempotencyKey, properties.retry().maxAttempts(),
                CorrelationIdFilter.current());
    }
}
