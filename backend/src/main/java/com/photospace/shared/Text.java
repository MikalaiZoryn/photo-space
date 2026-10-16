package com.photospace.shared;

/** Normalization for user-entered text. */
public final class Text {

    private Text() {}

    /** Strips surrounding whitespace; null stays null. */
    public static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /** Strips surrounding whitespace and turns a blank value into null, for optional fields. */
    public static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A {@code LIKE} pattern that matches {@code value} anywhere, with wildcards in it escaped. */
    public static String containsPattern(String value) {
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
