package com.photospace.jobs;

import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.CorrelationIdFilter;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /** The workspace's jobs, optionally of one status, most recently changed first. */
    @Transactional(readOnly = true)
    public PageView<JobView> list(UUID workspaceId, JobStatus status, PageQuery page) {
        return PageView.of(store.list(workspaceId, status, page.size(), page.offset()), page,
                store.count(workspaceId, status));
    }

    /** The newest job of each given subject in the workspace, by subject ID. Subjects without a job are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, JobView> latestBySubject(UUID workspaceId, Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return store.latestBySubject(workspaceId, subjectIds).stream()
                .collect(Collectors.toMap(JobView::subjectId, Function.identity()));
    }
}
