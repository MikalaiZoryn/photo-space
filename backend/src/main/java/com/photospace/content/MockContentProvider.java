package com.photospace.content;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deterministic provider for local development and tests: no network, no credentials, and no photo leaves
 * the machine. It builds plain sample captions from the session type and notes, so the whole flow (jobs,
 * drafts, editing, approval) works without an AI account. Selected with {@code app.content.provider=mock}.
 */
class MockContentProvider implements ContentGenerationProvider {

    static final String PROVIDER = "mock";
    static final String MODEL = "mock-captions-1";

    private static final String[] OPENINGS = {
        "A few favourite frames from this %s session.",
        "Some days are made for slowing down. This %s session was one of them.",
        "Light, laughter, and a little bit of magic: this %s session in a few frames.",
        "Behind every frame is a story. Here is a glimpse of this %s session.",
        "Simple moments, beautifully real. More from this %s session.",
    };

    private final Duration delay;

    MockContentProvider(Duration delay) {
        this.delay = delay;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        pause();
        String type = request.sessionType() == null || request.sessionType().isBlank()
                ? "photo"
                : request.sessionType().strip().toLowerCase(Locale.ROOT);
        String notes = firstSentence(request.notes());
        List<GenerationResult.Caption> captions = new ArrayList<>();
        for (int i = 0; i < request.captionCount(); i++) {
            String caption = OPENINGS[i % OPENINGS.length].formatted(type) + (notes == null ? "" : " " + notes);
            captions.add(new GenerationResult.Caption(
                    caption,
                    request.includeCallToAction() ? "Planning your own session? Send me a message." : null,
                    request.includeHashtags() ? hashtags(type) : List.of()));
        }
        List<String> order = new ArrayList<>(request.photos().stream().map(GenerationRequest.Photo::ref).toList());
        List<String> warnings = notes == null
                ? List.of("No session notes were given, so these captions are general. Add details you want mentioned.")
                : List.of();
        return new GenerationResult(
                captions,
                order,
                "Sample order from the mock provider: the photos as you selected them.",
                List.of("Share one photo per Story frame with a question sticker.",
                        "A short Reel that moves through the carousel photos in order."),
                warnings,
                null,
                null);
    }

    @Override
    public PlanResult plan(PlanRequest request) {
        pause();
        String type = request.sessionType() == null || request.sessionType().isBlank()
                ? "photo"
                : request.sessionType().strip().toLowerCase(Locale.ROOT);
        String area = request.studioArea() == null ? "" : " in " + request.studioArea();
        List<String> all = request.photos().stream().map(GenerationRequest.Photo::ref).toList();
        List<String> unused = new ArrayList<>(all);
        long feedSlots = request.slots().stream().filter(s -> s.format() == PlanRequest.SlotFormat.FEED).count();
        int feedSeen = 0;
        List<PlanResult.Post> posts = new ArrayList<>();
        for (PlanRequest.Slot slot : request.slots()) {
            List<String> photos;
            if (slot.format() == PlanRequest.SlotFormat.FEED) {
                feedSeen++;
                long laterFeedSlots = feedSlots - feedSeen;
                // The delivery carousel gets its minimum first; later posts share what is left.
                int wanted = slot.stage() == PostStage.DELIVERY
                        ? (int) Math.min(slot.maxPhotos(), Math.max(slot.minPhotos(), unused.size() - laterFeedSlots))
                        : slot.minPhotos();
                photos = new ArrayList<>(unused.subList(0, Math.min(wanted, unused.size())));
                unused.removeAll(photos);
            } else {
                photos = all.subList(0, Math.min(slot.maxPhotos(), all.size()));
            }
            if (photos.isEmpty()) {
                continue;
            }
            boolean story = slot.format() == PlanRequest.SlotFormat.STORY;
            String caption = switch (slot.stage()) {
                case SNEAK_PEEK -> "A first peek at this %s session%s. The full gallery is on its way.";
                case DELIVERY -> "The gallery from this %s session%s is ready. A few favourite frames.";
                case DRIP -> "One more favourite from this %s session%s.";
            };
            posts.add(new PlanResult.Post(
                    slot.number(),
                    photos,
                    caption.formatted(type, area),
                    "Know someone planning a session? Send this to them.",
                    story ? List.of() : hashtags(type).subList(0, Math.min(5, hashtags(type).size())),
                    switch (slot.format()) {
                        case REEL -> "Sample Reel from the mock provider: the photos in order, cover on the first.";
                        case STORY -> "Sample Story from the mock provider: one photo per frame, a question sticker.";
                        case FEED -> "";
                    }));
        }
        return new PlanResult(posts, List.of(), null, null);
    }

    private void pause() {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentGenerationException(ContentGenerationException.Kind.UNAVAILABLE, e);
        }
    }

    private static List<String> hashtags(String type) {
        String typeTag = type.replaceAll("[^\\p{L}\\p{N}]", "");
        List<String> tags = new ArrayList<>();
        if (!typeTag.isEmpty()) {
            tags.add(typeTag + "photography");
        }
        tags.addAll(List.of("photography", "photooftheday", "momentsthatmatter"));
        return tags;
    }

    private static String firstSentence(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        String first = notes.strip().split("(?<=[.!?])\\s+|\\R", 2)[0].strip();
        return first.length() > 200 ? first.substring(0, 199).strip() + "…" : first;
    }
}
