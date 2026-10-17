package com.photospace.content;

import com.photospace.content.ContentGenerationException.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a provider's proposal into drafts that fit the product's rules, deterministically. Unusable parts are
 * dropped (a caption that is too long, an invalid hashtag); if no caption survives, the whole answer is
 * {@link Kind#INVALID_OUTPUT}, which is retried.
 */
final class ContentValidator {

    static final int MAX_SUGGESTION_CHARS = 500;
    static final int MAX_STORY_IDEAS = 5;
    static final int MAX_WARNINGS = 10;

    private ContentValidator() {}

    static ValidatedContent validate(GenerationResult result, GenerationRequest request) {
        if (result == null) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT);
        }
        List<DraftText> captions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GenerationResult.Caption proposed : nonNull(result.captions())) {
            if (proposed == null || captions.size() == request.captionCount()) {
                continue;
            }
            String caption = text(proposed.caption());
            if (caption == null || !seen.add(caption)) {
                continue;
            }
            String callToAction = request.includeCallToAction() ? text(proposed.callToAction()) : null;
            if (callToAction != null && callToAction.length() > ContentPrompt.MAX_CALL_TO_ACTION_CHARS) {
                callToAction = null;
            }
            List<String> hashtags = request.includeHashtags()
                    ? Hashtags.lenient(proposed.hashtags(), ContentPrompt.MAX_HASHTAGS)
                    : List.of();
            DraftText draft = new DraftText(caption, callToAction, hashtags);
            if (draft.postLength() <= ContentPrompt.MAX_CAPTION_CHARS) {
                captions.add(draft);
            }
        }
        if (captions.isEmpty()) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT);
        }
        return new ValidatedContent(
                captions,
                carouselOrder(result.carouselOrder(), request.photos()),
                suggestion(result.carouselRationale()),
                suggestions(result.storyIdeas(), MAX_STORY_IDEAS),
                suggestions(result.warnings(), MAX_WARNINGS));
    }

    /**
     * Positions into the request's photos in the suggested order. Unknown and repeated references are ignored,
     * and photos the answer left out follow in selection order, so the result always names every photo once.
     */
    static List<Integer> carouselOrder(List<String> refs, List<GenerationRequest.Photo> photos) {
        Map<String, Integer> byRef = new HashMap<>();
        for (int i = 0; i < photos.size(); i++) {
            byRef.put(photos.get(i).ref(), i);
        }
        LinkedHashSet<Integer> order = new LinkedHashSet<>();
        for (String ref : nonNull(refs)) {
            Integer index = ref == null ? null : byRef.get(ref.strip());
            if (index != null) {
                order.add(index);
            }
        }
        for (int i = 0; i < photos.size(); i++) {
            order.add(i);
        }
        return List.copyOf(order);
    }

    private static List<String> suggestions(List<String> values, int max) {
        List<String> result = new ArrayList<>();
        for (String value : nonNull(values)) {
            String cleaned = suggestion(value);
            if (cleaned != null && result.size() < max && !result.contains(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static String suggestion(String value) {
        String cleaned = text(value);
        if (cleaned == null || cleaned.length() <= MAX_SUGGESTION_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_SUGGESTION_CHARS - 1).strip() + "…";
    }

    /** Strips whitespace and control characters other than line breaks and tabs; blank becomes null. */
    static String text(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cc}&&[^\\n\\t]]|\\p{Cs}", "").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Drafts ready to store, with the carousel order as positions into the request's photos. */
    record ValidatedContent(
            List<DraftText> captions,
            List<Integer> carouselOrder,
            String carouselRationale,
            List<String> storyIdeas,
            List<String> warnings) {}
}
