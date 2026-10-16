package com.photospace.shared.web;

/**
 * The resource does not exist or the caller may not know that it exists. Both cases map to the same 404, so
 * IDs from other workspaces reveal nothing.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("Not found", null, false, false);
    }
}
