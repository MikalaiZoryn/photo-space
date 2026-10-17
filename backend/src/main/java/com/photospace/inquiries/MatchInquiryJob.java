package com.photospace.inquiries;

import com.photospace.jobs.Job;
import com.photospace.jobs.JobHandler;
import org.springframework.stereotype.Component;

/**
 * Looks for clients with the inquiry's email and creates its review task. Safe to run more than once: it acts
 * only on a {@code RECEIVED} inquiry, in one transaction that also moves it on, so a redelivered job creates
 * nothing. When the attempts run out, the inquiry goes to {@code NEEDS_REVIEW} so a person looks at it.
 */
@Component
class MatchInquiryJob implements JobHandler {

    static final String TYPE = "match-inquiry";

    private final InquiryService inquiries;

    MatchInquiryJob(InquiryService inquiries) {
        this.inquiries = inquiries;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(Job job) {
        inquiries.match(job.workspaceId(), job.subjectId(), false);
    }

    @Override
    public void giveUp(Job job) {
        inquiries.match(job.workspaceId(), job.subjectId(), true);
    }
}
