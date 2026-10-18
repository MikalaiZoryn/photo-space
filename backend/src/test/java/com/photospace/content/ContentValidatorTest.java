package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.content.ContentValidator.ValidatedContent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentValidatorTest {

    private static final GenerationRequest.Brand NO_BRAND = new GenerationRequest.Brand(null, null, null, null);

    private static GenerationRequest request(int photos, int captions, boolean hashtags, boolean callToAction) {
        List<GenerationRequest.Photo> list = new ArrayList<>();
        for (int i = 1; i <= photos; i++) {
            list.add(new GenerationRequest.Photo("photo-" + i, new byte[] {1}));
        }
        return new GenerationRequest(NO_BRAND, "Family", null, "Notes", list, captions, hashtags, callToAction);
    }

    private static GenerationResult result(List<GenerationResult.Caption> captions, List<String> order) {
        return new GenerationResult(captions, order, " Cover first. ", List.of("Idea one", " ", "Idea one", "Idea two"),
                List.of("Check the date."), 10, 5);
    }

    @Test
    void keepsUsableCaptionsAndNormalizesHashtags() {
        ValidatedContent content = ContentValidator.validate(result(List.of(
                        new GenerationResult.Caption("  First caption\r\n", " Book now ", List.of("#Family", "family", "bad tag", "#", "zdjęcia_2026")),
                        new GenerationResult.Caption("First caption", "dup", List.of()),
                        new GenerationResult.Caption("   ", null, List.of()),
                        new GenerationResult.Caption("Second\u0000 caption", null, null),
                        new GenerationResult.Caption("Third, over the requested count", null, List.of())),
                List.of("photo-2", "photo-1")), request(2, 2, true, true));

        assertThat(content.captions()).containsExactly(
                new DraftText("First caption", "Book now", List.of("Family", "zdjęcia_2026")),
                new DraftText("Second caption", null, List.of()));
        assertThat(content.carouselOrder()).containsExactly(1, 0);
        assertThat(content.carouselRationale()).isEqualTo("Cover first.");
        assertThat(content.storyIdeas()).containsExactly("Idea one", "Idea two");
        assertThat(content.warnings()).containsExactly("Check the date.");
    }

    @Test
    void keepsAtMostFiveHashtags() {
        ValidatedContent content = ContentValidator.validate(result(List.of(new GenerationResult.Caption("Caption", null,
                List.of("a1", "b2", "c3", "d4", "e5", "f6", "g7"))), List.of()), request(1, 1, true, false));

        assertThat(content.captions().getFirst().hashtags()).containsExactly("a1", "b2", "c3", "d4", "e5");
    }

    @Test
    void dropsWhatWasNotRequested() {
        ValidatedContent content = ContentValidator.validate(result(List.of(
                new GenerationResult.Caption("Caption", "Book now", List.of("family"))), List.of()),
                request(1, 1, false, false));

        assertThat(content.captions()).containsExactly(new DraftText("Caption", null, List.of()));
    }

    @Test
    void dropsCaptionsThatWouldNotFitAnInstagramPost() {
        String long1 = "a".repeat(2191);
        ValidatedContent content = ContentValidator.validate(result(List.of(
                        new GenerationResult.Caption(long1, null, List.of("toolong")),
                        new GenerationResult.Caption("b".repeat(2200), null, List.of()),
                        new GenerationResult.Caption("short", "x".repeat(301), List.of())),
                List.of()), request(1, 3, true, true));

        assertThat(content.captions()).containsExactly(
                new DraftText("b".repeat(2200), null, List.of()), new DraftText("short", null, List.of()));
    }

    @Test
    void completesTheCarouselOrderWithEveryPhotoOnce() {
        List<GenerationRequest.Photo> photos = request(4, 1, false, false).photos();

        assertThat(ContentValidator.carouselOrder(List.of("photo-3", "photo-9", "photo-3", " photo-1 "), photos))
                .containsExactly(2, 0, 1, 3);
        assertThat(ContentValidator.carouselOrder(null, photos)).containsExactly(0, 1, 2, 3);
    }

    @Test
    void anAnswerWithoutAUsableCaptionIsInvalid() {
        assertThatThrownBy(() -> ContentValidator.validate(result(List.of(), List.of()), request(1, 1, false, false)))
                .isInstanceOfSatisfying(ContentGenerationException.class, e -> assertThat(e.kind()).isEqualTo(Kind.INVALID_OUTPUT));
        assertThatThrownBy(() -> ContentValidator.validate(null, request(1, 1, false, false)))
                .isInstanceOf(ContentGenerationException.class);
        assertThat(Kind.INVALID_OUTPUT.retryable()).isTrue();
    }

    @Test
    void shortensLongSuggestions() {
        GenerationResult answer = new GenerationResult(List.of(new GenerationResult.Caption("c", null, List.of())),
                List.of(), "r".repeat(600), List.of(), List.of(), null, null);

        String rationale = ContentValidator.validate(answer, request(1, 1, false, false)).carouselRationale();
        assertThat(rationale).hasSize(ContentValidator.MAX_SUGGESTION_CHARS).endsWith("…");
    }
}
