package com.photospace.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Hashtags are stored without the # sign: letters, digits, and underscores only, as Instagram allows. */
final class Hashtags {

    private static final Pattern VALID = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]{1,100}");

    private Hashtags() {}

    /** Strips a leading # and surrounding whitespace. */
    static String clean(String tag) {
        String value = tag == null ? "" : tag.strip();
        while (value.startsWith("#")) {
            value = value.substring(1);
        }
        return value;
    }

    static boolean valid(String cleaned) {
        return VALID.matcher(cleaned).matches();
    }

    /**
     * For model output: cleans each tag, drops invalid ones and repeats (ignoring case), and keeps at most
     * {@code max}.
     */
    static List<String> lenient(List<String> tags, int max) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String tag : tags == null ? List.<String>of() : tags) {
            String cleaned = clean(tag);
            if (valid(cleaned) && seen.add(cleaned.toLowerCase(Locale.ROOT)) && result.size() < max) {
                result.add(cleaned);
            }
        }
        return result;
    }
}
