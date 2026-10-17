package com.photospace.content;

import java.util.List;

/**
 * What a provider proposed, before validation. Lists may be empty and text may be blank or too long;
 * {@link ContentValidator} decides what is usable.
 *
 * @param carouselOrder photo references in the suggested carousel order
 * @param warnings missing information or things the photographer should check
 */
public record GenerationResult(
        List<Caption> captions,
        List<String> carouselOrder,
        String carouselRationale,
        List<String> storyIdeas,
        List<String> warnings,
        Integer inputTokens,
        Integer outputTokens) {

    public record Caption(String caption, String callToAction, List<String> hashtags) {}
}
