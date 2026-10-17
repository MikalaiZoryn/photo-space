package com.photospace.mail;

/**
 * An email could not be sent. The message is a fixed sentence or an error class name: never an address, a body,
 * or provider output.
 */
public class EmailDeliveryException extends Exception {

    private final boolean permanent;

    public EmailDeliveryException(String safeMessage, boolean permanent, Throwable cause) {
        super(safeMessage, cause);
        this.permanent = permanent;
    }

    /** True when trying again cannot help, such as a rejected recipient or a missing sender configuration. */
    public boolean permanent() {
        return permanent;
    }
}
