package com.photospace.assets;

/**
 * Cleans a browser-supplied file name for display. The name is never used in object keys or file paths; this
 * only removes what could mislead a reader: directory parts, control characters, and invisible formatting
 * characters such as right-to-left overrides.
 */
final class FileNames {

    private FileNames() {}

    /** The cleaned name, or null if nothing printable is left. */
    static String forDisplay(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder(name.length());
        name.codePoints()
                .filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
                .map(cp -> Character.isWhitespace(cp) ? ' ' : cp)
                .forEach(clean::appendCodePoint);
        String result = clean.toString().replaceAll(" {2,}", " ").strip();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? null : result;
    }

    /** The download name of a photo's preview: {@code IMG_0042.JPG} becomes {@code IMG_0042-preview.jpg}. */
    static String previewOf(String displayName) {
        int dot = displayName.lastIndexOf('.');
        String stem = dot > 0 ? displayName.substring(0, dot) : displayName;
        return stem + "-preview.jpg";
    }
}
