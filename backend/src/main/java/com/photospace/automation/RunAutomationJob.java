package com.photospace.automation;

import com.photospace.jobs.Job;
import com.photospace.jobs.JobHandler;
import org.springframework.stereotype.Component;

/**
 * Carries out one automation run: creates its task. Safe to run more than once: it acts only on a pending run,
 * and a run can own only one task. A failure is retried with the job's backoff; when the attempts run out, the
 * run is marked failed and can be retried by a member.
 */
@Component
class RunAutomationJob implements JobHandler {

    static final String TYPE = "run-automation";

    static final String GAVE_UP = "Creating the task failed repeatedly. Retry the run.";

    private final AutomationService automation;

    RunAutomationJob(AutomationService automation) {
        this.automation = automation;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(Job job) {
        automation.execute(job.workspaceId(), job.subjectId());
    }

    @Override
    public void giveUp(Job job) {
        automation.fail(job.workspaceId(), job.subjectId(), GAVE_UP);
    }
}
