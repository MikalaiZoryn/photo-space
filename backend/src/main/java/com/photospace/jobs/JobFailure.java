package com.photospace.jobs;

/**
 * A retryable failure whose message is a fixed, safe sentence, stored as the job's {@code last_error}. Other
 * exceptions are stored by class name only, because their messages may contain keys, URLs, or payloads.
 */
public class JobFailure extends RuntimeException {

    public JobFailure(String safeMessage) {
        super(safeMessage, null, false, false);
    }

    public JobFailure(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
