package com.photospace.shared.web;

/** The caller can see the resource but lacks the role for this action. Maps to 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("Forbidden", null, false, false);
    }
}
