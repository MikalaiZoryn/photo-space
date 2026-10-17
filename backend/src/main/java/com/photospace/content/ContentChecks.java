package com.photospace.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic review hints for a draft: account handles, links, email addresses, phone numbers, and prices
 * that appear in the draft but not in the photographer's own text (notes and brand profile). The AI may have
 * invented them, so the photographer should check them before approving. These are hints, not blocks.
 */
final class ContentChecks {

    private record Check(Pattern pattern, String what) {}

    private static final List<Check> CHECKS = List.of(
            new Check(Pattern.compile("(?<![\\w.@])@[A-Za-z0-9._]{2,30}"), "the account"),
            new Check(Pattern.compile("(?i)\\bhttps?://\\S+|\\bwww\\.\\S+"), "the link"),
            new Check(Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"), "the email address"),
            new Check(Pattern.compile("\\+?\\d[\\d ()-]{6,}\\d"), "the phone number"),
            new Check(Pattern.compile("(?i)[$€£]\\s?\\d[\\d.,]*|(?<![\\p{L}\\p{N}])\\d[\\d.,]*\\s?(?:zł|pln|eur|usd|gbp)(?![\\p{L}\\p{N}])"), "the price"));

    private ContentChecks() {}

    /** Hints for one draft; {@code sources} is the text the photographer wrote themselves. */
    static List<String> hints(DraftText draft, List<String> sources) {
        String source = String.join("\n", sources.stream().filter(s -> s != null).toList()).toLowerCase(Locale.ROOT);
        String text = draft.callToAction() == null ? draft.caption() : draft.caption() + "\n" + draft.callToAction();
        Set<String> hints = new LinkedHashSet<>();
        for (Check check : CHECKS) {
            Matcher matcher = check.pattern().matcher(text);
            while (matcher.find()) {
                String found = trimPunctuation(matcher.group());
                if (!found.isEmpty() && !source.contains(found.toLowerCase(Locale.ROOT))) {
                    hints.add("Mentions " + check.what() + " “" + found
                            + "”, which is not in your notes or brand profile. Check it before approving.");
                }
            }
        }
        return new ArrayList<>(hints);
    }

    private static String trimPunctuation(String value) {
        return value.strip().replaceAll("[.,;:!?)\\]]+$", "");
    }
}
