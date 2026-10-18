package com.photospace.content;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Proposes plans for sessions whose photos have settled: the last photo became ready more than
 * {@code settle-after} ago, but within {@code lookback}, and the session has no plan yet. Safe on several
 * instances at once: {@link ContentPlanService#proposeAutomatically} starts a generation only for the call that
 * creates the plan row.
 */
@Component
@ConditionalOnProperty("app.content.plans.enabled")
class PlanProposalScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlanProposalScheduler.class);
    private static final int BATCH = 20;

    private final JdbcClient jdbc;
    private final ContentPlanService plans;
    private final ContentProperties.Plans settings;

    PlanProposalScheduler(JdbcClient jdbc, ContentPlanService plans, ContentProperties properties) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.settings = properties.plans();
    }

    @Scheduled(fixedDelayString = "${app.content.plans.interval}", initialDelayString = "${app.content.plans.interval}")
    void scheduledRun() {
        try {
            int proposed = proposeSettled();
            if (proposed > 0) {
                log.info("Proposed {} content plan(s)", proposed);
            }
        } catch (RuntimeException e) {
            log.warn("Content plan proposal run failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Proposes plans for up to one batch of settled sessions and returns how many it started. */
    int proposeSettled() {
        List<Settled> settled = jdbc.sql("""
                        SELECT a.workspace_id, a.session_id
                          FROM photo_assets a
                          JOIN photo_sessions s ON s.workspace_id = a.workspace_id AND s.id = a.session_id
                          LEFT JOIN content_studio_settings c ON c.workspace_id = a.workspace_id
                         WHERE a.status = 'READY' AND s.archived_at IS NULL AND coalesce(c.auto_plans, true)
                           AND NOT EXISTS (SELECT 1 FROM content_plans p
                                            WHERE p.workspace_id = a.workspace_id AND p.session_id = a.session_id)
                         GROUP BY a.workspace_id, a.session_id
                        HAVING max(a.processed_at) <= now() - make_interval(secs => :settle)
                           AND max(a.processed_at) > now() - make_interval(secs => :lookback)
                         ORDER BY max(a.processed_at)
                         LIMIT :limit
                        """)
                .param("settle", (double) settings.settleAfter().toSeconds())
                .param("lookback", (double) settings.lookback().toSeconds())
                .param("limit", BATCH)
                .query((rs, n) -> new Settled(rs.getObject("workspace_id", UUID.class),
                        rs.getObject("session_id", UUID.class)))
                .list();
        int proposed = 0;
        for (Settled session : settled) {
            try {
                if (plans.proposeAutomatically(session.workspaceId(), session.sessionId())) {
                    proposed++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not propose a plan for session {}: {}", session.sessionId(),
                        e.getClass().getSimpleName());
            }
        }
        return proposed;
    }

    private record Settled(UUID workspaceId, UUID sessionId) {}
}
