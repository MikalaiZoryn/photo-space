package com.photospace.content;

import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.content.PlanRequest.Slot;
import com.photospace.content.PlanRequest.SlotFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a provider's proposed plan into posts that fit the product's rules, deterministically. Each slot keeps
 * the first usable post the answer gave for it. Unknown and repeated photos are dropped, a feed post never reuses
 * a photo of an earlier feed post, photo counts are capped by the slot and the format, and the text follows the
 * caption rules. A post left with no photos or no caption is dropped; if none survives, the answer is
 * {@link Kind#INVALID_OUTPUT}, which is retried.
 */
final class PlanValidator {

    static final int MAX_IDEA_CHARS = 1000;

    private PlanValidator() {}

    static ValidatedPlan validate(PlanResult result, PlanRequest request) {
        if (result == null || result.posts() == null) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT);
        }
        Map<String, Integer> byRef = new HashMap<>();
        for (int i = 0; i < request.photos().size(); i++) {
            byRef.put(request.photos().get(i).ref(), i);
        }
        Map<Integer, PlanResult.Post> bySlot = new HashMap<>();
        for (PlanResult.Post post : result.posts()) {
            if (post != null && post.slot() != null) {
                bySlot.putIfAbsent(post.slot(), post);
            }
        }
        Set<Integer> usedInFeed = new HashSet<>();
        List<PlannedPost> posts = new ArrayList<>();
        for (Slot slot : request.slots()) {
            PlanResult.Post proposed = bySlot.get(slot.number());
            if (proposed == null) {
                continue;
            }
            boolean feed = slot.format() == SlotFormat.FEED;
            LinkedHashSet<Integer> photos = new LinkedHashSet<>();
            for (String ref : proposed.photos() == null ? List.<String>of() : proposed.photos()) {
                Integer index = ref == null ? null : byRef.get(ref.strip());
                if (index != null && !(feed && usedInFeed.contains(index)) && photos.size() < slot.maxPhotos()) {
                    photos.add(index);
                }
            }
            String caption = ContentValidator.text(proposed.caption());
            if (photos.isEmpty() || caption == null) {
                continue;
            }
            String callToAction = ContentValidator.text(proposed.callToAction());
            if (callToAction != null && callToAction.length() > ContentPrompt.MAX_CALL_TO_ACTION_CHARS) {
                callToAction = null;
            }
            List<String> hashtags = slot.format() == SlotFormat.STORY
                    ? List.of()
                    : Hashtags.lenient(proposed.hashtags(), ContentPrompt.MAX_HASHTAGS);
            DraftText text = new DraftText(caption, callToAction, hashtags);
            if (text.postLength() > ContentPrompt.MAX_CAPTION_CHARS) {
                continue;
            }
            PostFormat format = switch (slot.format()) {
                case FEED -> photos.size() == 1 ? PostFormat.SINGLE : PostFormat.CAROUSEL;
                case REEL -> PostFormat.REEL;
                case STORY -> PostFormat.STORY;
            };
            if (feed) {
                usedInFeed.addAll(photos);
            }
            String idea = slot.format() == SlotFormat.FEED ? null : idea(proposed.idea());
            posts.add(new PlannedPost(slot, format, List.copyOf(photos), text, idea));
        }
        if (posts.isEmpty()) {
            throw new ContentGenerationException(Kind.INVALID_OUTPUT);
        }
        return new ValidatedPlan(posts, ContentValidator.suggestions(result.warnings(), ContentValidator.MAX_WARNINGS));
    }

    private static String idea(String value) {
        String cleaned = ContentValidator.text(value);
        if (cleaned == null || cleaned.length() <= MAX_IDEA_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_IDEA_CHARS - 1).strip() + "…";
    }

    /** A post ready to store, with its photos as positions into the request's photos, in posting order. */
    record PlannedPost(Slot slot, PostFormat format, List<Integer> photos, DraftText text, String idea) {}

    record ValidatedPlan(List<PlannedPost> posts, List<String> warnings) {}
}
