package com.photospace.mail;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AccountSuspendedException;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;

/**
 * Sends through the Amazon SES v2 API. The sender address must be a verified SES identity; while the account is
 * in the SES sandbox, so must every recipient (see docs/operations.md). SDK types stay inside this class.
 */
class SesEmailSender implements EmailSender, AutoCloseable {

    private final SesV2Client ses;
    private final String from;

    SesEmailSender(SesV2Client ses, String from) {
        this.ses = ses;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) throws EmailDeliveryException {
        try {
            ses.sendEmail(request -> request
                    .fromEmailAddress(from)
                    .destination(destination -> destination.toAddresses(message.to()))
                    .content(content -> content.simple(simple -> simple
                            .subject(subject -> subject.data(message.subject()).charset("UTF-8"))
                            .body(body -> body.text(text -> text.data(message.textBody()).charset("UTF-8"))))));
        } catch (MessageRejectedException | MailFromDomainNotVerifiedException e) {
            // A rejected recipient or an unverified sender: the same message would be rejected again.
            throw new EmailDeliveryException(e.getClass().getSimpleName(), true, e);
        } catch (AccountSuspendedException | SendingPausedException e) {
            // Needs an operator; retrying within the job's bounded attempts does no harm.
            throw new EmailDeliveryException(e.getClass().getSimpleName(), false, e);
        } catch (SdkException e) {
            throw new EmailDeliveryException(e.getClass().getSimpleName(), false, e);
        }
    }

    /** Spring calls this on shutdown (an inferred destroy method). */
    @Override
    public void close() {
        ses.close();
    }
}
