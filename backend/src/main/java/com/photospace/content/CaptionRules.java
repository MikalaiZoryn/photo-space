package com.photospace.content;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic caption checks from the Instagram research behind Milestone 13, shown as fix-it chips on every
 * draft. Only {@link Severity#BLOCKER} rules stop a save, and the editor enforces those separately; this class
 * explains them. A draft saved before the 5-hashtag cap still reads, with a blocker until it is edited.
 */
final class CaptionRules {

    enum Severity {
        BLOCKER,
        WARNING,
        TIP
    }

    enum Code {
        HASHTAG_LIMIT,
        SESSION_TYPE,
        LOCATION,
        CALL_TO_ACTION,
        SEND_PROMPT
    }

    record CaptionRule(Code code, Severity severity, String message) {}

    /** English words that ask people to pass a post on, for the sends-per-reach tip. */
    private static final Pattern SEND_WORDS = Pattern.compile("(?i)\\b(send|share|tag|forward)\\b");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTERS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private CaptionRules() {}

    /**
     * The rules a draft breaks, most serious first. {@code sessionType} and {@code studioArea} may be null when
     * the session has no type or the workspace has not set its area.
     */
    static List<CaptionRule> check(DraftText draft, String sessionType, String studioArea) {
        List<CaptionRule> rules = new ArrayList<>();
        if (draft.hashtags().size() > ContentPrompt.MAX_HASHTAGS) {
            rules.add(new CaptionRule(Code.HASHTAG_LIMIT, Severity.BLOCKER, "Instagram allows at most "
                    + ContentPrompt.MAX_HASHTAGS + " hashtags. Remove " + (draft.hashtags().size()
                            - ContentPrompt.MAX_HASHTAGS) + " before saving."));
        }
        String text = normalized(draft.caption() + " " + (draft.callToAction() == null ? "" : draft.callToAction()));
        String tags = normalized(String.join(" ", draft.hashtags()));
        String type = sessionType == null ? null : sessionType.strip();
        if (type != null && !type.isEmpty() && words(type).stream().noneMatch(word -> text.contains(word))) {
            rules.add(new CaptionRule(Code.SESSION_TYPE, Severity.WARNING,
                    "Name the kind of session (" + type + ") so people searching for it find the post."));
        }
        if (studioArea == null) {
            rules.add(new CaptionRule(Code.LOCATION, Severity.TIP,
                    "Set your studio area in Settings, Content Studio, to check that captions name it."));
        } else if (!mentions(text, tags, studioArea)) {
            rules.add(new CaptionRule(Code.LOCATION, Severity.WARNING,
                    "Mention " + studioArea + " so people looking for a photographer nearby find the post."));
        }
        if (draft.callToAction() == null) {
            rules.add(new CaptionRule(Code.CALL_TO_ACTION, Severity.WARNING,
                    "Add one call to action, such as inviting people to book or save the post."));
        } else if (!SEND_WORDS.matcher(draft.caption() + " " + draft.callToAction()).find()) {
            rules.add(new CaptionRule(Code.SEND_PROMPT, Severity.TIP,
                    "Try asking people to send the post to someone it would suit. Shares help it reach more people."));
        }
        return rules;
    }

    /** True if the caption names the area: its first part (before a comma), in the text or squeezed into a hashtag. */
    private static boolean mentions(String text, String tags, String studioArea) {
        String area = normalized(studioArea.split(",", 2)[0]).strip();
        if (area.isEmpty()) {
            return true;
        }
        return text.contains(area) || tags.contains(NOT_LETTERS.matcher(area).replaceAll(""));
    }

    /** Words of three letters or more, so "Family session" matches a caption that says "family". */
    private static List<String> words(String value) {
        List<String> words = new ArrayList<>();
        for (String word : NOT_LETTERS.split(normalized(value))) {
            if (word.length() >= 3 && !word.equals("session") && !word.equals("shoot")) {
                words.add(word);
            }
        }
        return words.isEmpty() ? List.of(normalized(value).strip()) : words;
    }

    /** Lower case without accents, so "Kraków" matches "krakow". */
    private static String normalized(String value) {
        return MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
