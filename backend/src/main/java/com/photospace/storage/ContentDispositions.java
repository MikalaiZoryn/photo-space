package com.photospace.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** {@code Content-Disposition} values for downloads. */
final class ContentDispositions {

    private ContentDispositions() {}

    /**
     * {@code attachment} with the file name twice: an ASCII fallback for old clients, and the exact name as
     * RFC 8187 UTF-8 ({@code filename*}), which browsers prefer. Quotes, backslashes, control characters, and
     * non-ASCII characters never reach the quoted fallback.
     */
    static String attachment(String filename) {
        StringBuilder fallback = new StringBuilder(filename.length());
        filename.codePoints().forEach(cp -> fallback.append(cp >= 0x20 && cp < 0x7f && cp != '"' && cp != '\\'
                ? (char) cp
                : '_'));
        // URLEncoder is form encoding: undo "+" for spaces, and keep the result to RFC 8187's attr-char set.
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
