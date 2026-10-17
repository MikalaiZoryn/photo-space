package com.photospace.mail;

/**
 * Sends email through the configured provider (D20). Implementations never log recipients or bodies.
 * Callers send from background jobs, never from a request, so a slow or failing provider only delays the job.
 */
public interface EmailSender {

    /**
     * Sends {@code message}, or throws: a {@link EmailDeliveryException#permanent() permanent} failure will not
     * go away by trying again; any other exception may.
     */
    void send(EmailMessage message) throws EmailDeliveryException;
}
