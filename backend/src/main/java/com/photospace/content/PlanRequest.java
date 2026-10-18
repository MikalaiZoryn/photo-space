package com.photospace.content;

import java.util.List;

/**
 * Everything a provider may use to write a session plan, and nothing else: the brand profile, the session type,
 * the studio's area, the photos' previews, and the posts to write. No client details, session title, location,
 * or session notes. All of it is untrusted data for the model, never instructions.
 *
 * @param slots the posts to write, in order; code decides their stage, format, and photo counts, never the model
 */
public record PlanRequest(
        GenerationRequest.Brand brand,
        String sessionType,
        String studioArea,
        List<GenerationRequest.Photo> photos,
        List<Slot> slots) {

    public PlanRequest {
        photos = List.copyOf(photos);
        slots = List.copyOf(slots);
    }

    /** One post to write. {@code number} counts from 1, as the result refers to it. */
    public record Slot(int number, PostStage stage, SlotFormat format, int minPhotos, int maxPhotos) {}

    /** A feed slot becomes a single image or a carousel, by its photo count. */
    public enum SlotFormat {
        FEED,
        REEL,
        STORY
    }
}
