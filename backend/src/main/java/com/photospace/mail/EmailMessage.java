package com.photospace.mail;

import java.util.Objects;

/**
 * A plain-text email to one recipient. Plain text only, so user-entered text such as a workspace name can never
 * become markup. Line breaks and other control characters are removed from the address and the subject, so
 * neither can add a header.
 */
public record EmailMessage(String to, String subject, String textBody) {

    public EmailMessage {
        to = singleLine(Objects.requireNonNull(to, "to")).strip();
        subject = singleLine(Objects.requireNonNull(subject, "subject")).strip();
        Objects.requireNonNull(textBody, "textBody");
        if (to.isEmpty()) {
            throw new IllegalArgumentException("Recipient is blank");
        }
    }

    /** Replaces line breaks and other control characters with spaces. */
    public static String singleLine(String text) {
        return text.replaceAll("\\p{Cntrl}", " ");
    }

    /** Never prints the recipient or the body, which can hold a credential such as an invitation link. */
    @Override
    public String toString() {
        return "EmailMessage[subject length " + subject.length() + ", body length " + textBody.length() + "]";
    }
}
