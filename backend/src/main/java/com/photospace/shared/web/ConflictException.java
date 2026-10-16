package com.photospace.shared.web;

/**
 * The request is valid but conflicts with the record's current state: it was changed by someone else, it is
 * archived, or a status change is not allowed. Maps to 409. The message is a fixed, user-facing sentence and
 * becomes the problem {@code detail}, so it must never contain record data.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String safeDetail) {
        super(safeDetail, null, false, false);
    }
}
