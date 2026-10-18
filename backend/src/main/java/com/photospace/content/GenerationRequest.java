package com.photospace.content;

import java.util.List;

/**
 * Everything a provider may use, and nothing else: the brand profile, the session type, the studio's area from
 * the Content Studio settings, the notes the photographer chose to send, and the selected photos' previews. No client name or contact details are part of
 * it. All of it is untrusted data for the model, never instructions.
 *
 * @param photos the selected photos in selection order; each has a reference such as {@code photo-1} that the
 *     result uses for the carousel order
 */
public record GenerationRequest(
        Brand brand,
        String sessionType,
        String studioArea,
        String notes,
        List<Photo> photos,
        int captionCount,
        boolean includeHashtags,
        boolean includeCallToAction) {

    public GenerationRequest {
        photos = List.copyOf(photos);
    }

    /** Any field may be null when the photographer left it empty. */
    public record Brand(String voice, String audience, String preferences, String avoid) {}

    /** A metadata-free JPEG preview (D11), at most 2048 px on its longer side. */
    public record Photo(String ref, byte[] jpeg) {}
}
