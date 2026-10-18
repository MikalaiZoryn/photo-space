package com.photospace.content;

import java.util.List;

/**
 * A provider's proposed plan, before validation. Posts may be missing, repeated, or name unknown photos;
 * {@link PlanValidator} decides what is usable.
 */
public record PlanResult(List<Post> posts, List<String> warnings, Integer inputTokens, Integer outputTokens) {

    /**
     * One proposed post.
     *
     * @param slot the request slot it fills
     * @param photos photo references in posting order
     * @param idea notes for a Reel or Story: frame order, cover, on-screen text, audio mood, stickers
     */
    public record Post(
            Integer slot, List<String> photos, String caption, String callToAction, List<String> hashtags,
            String idea) {}
}
