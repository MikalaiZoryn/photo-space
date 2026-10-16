package com.photospace.shared.web;

import java.util.Map;

/**
 * Validation that needs more than field annotations, such as checks against other fields or stored data.
 * Maps to the same 400 body as annotation validation: field name to message under {@code errors}.
 */
public class InvalidFieldsException extends RuntimeException {

    private final Map<String, String> errors;

    public InvalidFieldsException(Map<String, String> errors) {
        super("Request validation failed", null, false, false);
        this.errors = Map.copyOf(errors);
    }

    public static InvalidFieldsException of(String field, String message) {
        return new InvalidFieldsException(Map.of(field, message));
    }

    public Map<String, String> errors() {
        return errors;
    }
}
