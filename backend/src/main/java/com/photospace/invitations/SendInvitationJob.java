package com.photospace.invitations;

import com.photospace.invitations.InvitationDelivery.Outgoing;
import com.photospace.jobs.Job;
import com.photospace.jobs.JobHandler;
import com.photospace.mail.EmailDeliveryException;
import com.photospace.mail.EmailSender;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends one invitation email. It sends only while the invitation's email is {@code QUEUED}, so a redelivered
 * job after a recorded send does nothing. If the worker dies between sending and recording, the retry sends a
 * second email with a new link, and only that newest link works. A permanent provider failure, or running out
 * of attempts, marks the email {@code FAILED} so the owner can resend.
 */
@Component
class SendInvitationJob implements JobHandler {

    static final String TYPE = "send-invitation-email";

    private static final Logger log = LoggerFactory.getLogger(SendInvitationJob.class);

    private final InvitationDelivery delivery;
    private final EmailSender sender;

    SendInvitationJob(InvitationDelivery delivery, EmailSender sender) {
        this.delivery = delivery;
        this.sender = sender;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(Job job) throws EmailDeliveryException {
        Optional<Outgoing> outgoing = delivery.prepare(job.workspaceId(), job.subjectId());
        if (outgoing.isEmpty()) {
            return;
        }
        try {
            sender.send(outgoing.get().message());
        } catch (EmailDeliveryException e) {
            if (!e.permanent()) {
                throw e;
            }
            log.warn("Invitation {} email failed permanently: {}", job.subjectId(), e.getMessage());
            delivery.markFailed(job.workspaceId(), job.subjectId());
            return;
        }
        delivery.markSent(job.workspaceId(), job.subjectId(), outgoing.get().tokenHash());
    }

    @Override
    public void giveUp(Job job) {
        delivery.markFailed(job.workspaceId(), job.subjectId());
    }
}
