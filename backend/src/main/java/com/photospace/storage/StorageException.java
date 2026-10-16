package com.photospace.storage;

/**
 * A storage call failed. The message is a fixed, safe sentence; the provider's exception is kept as the cause
 * for logs and never shown to users.
 */
public class StorageException extends RuntimeException {

    StorageException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}
