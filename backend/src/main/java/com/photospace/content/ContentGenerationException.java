package com.photospace.content;

/**
 * A failed generation, as a provider-neutral kind. The message is a fixed sentence that is safe to store and
 * show; provider messages and payloads are never part of it.
 */
public class ContentGenerationException extends RuntimeException {

    public enum Kind {
        /** Too many requests: worth retrying later. */
        RATE_LIMITED(true, "The AI provider is busy right now."),
        /** A timeout, network failure, or server error: worth retrying later. */
        UNAVAILABLE(true, "The AI provider could not be reached or did not answer in time."),
        /** The answer could not be used: retrying may give a usable one. */
        INVALID_OUTPUT(true, "The AI provider's answer could not be used."),
        /** The provider refused this request; the same request would be refused again. */
        REFUSED(false, "The AI provider declined to write content for this request. Try other photos or notes."),
        /** The provider rejected the request itself, such as an image it cannot read. */
        REJECTED(false, "The AI provider rejected the request."),
        /** Missing or rejected credentials, or no access to the model. Needs an operator. */
        NOT_CONFIGURED(false, "AI generation is not set up correctly. Ask the administrator to check it.");

        private final boolean retryable;
        private final String userMessage;

        Kind(boolean retryable, String userMessage) {
            this.retryable = retryable;
            this.userMessage = userMessage;
        }

        public boolean retryable() {
            return retryable;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    private final Kind kind;

    public ContentGenerationException(Kind kind, Throwable cause) {
        super(kind.userMessage(), cause);
        this.kind = kind;
    }

    public ContentGenerationException(Kind kind) {
        this(kind, null);
    }

    public Kind kind() {
        return kind;
    }
}
