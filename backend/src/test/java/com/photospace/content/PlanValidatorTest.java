package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.photospace.content.ContentGenerationException.Kind;
import com.photospace.content.PlanRequest.Slot;
import com.photospace.content.PlanRequest.SlotFormat;
import com.photospace.content.PlanValidator.PlannedPost;
import com.photospace.content.PlanValidator.ValidatedPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanValidatorTest {

    private static final List<Slot> SLOTS = List.of(
            new Slot(1, PostStage.SNEAK_PEEK, SlotFormat.FEED, 1, 3),
            new Slot(2, PostStage.SNEAK_PEEK, SlotFormat.STORY, 1, 3),
            new Slot(3, PostStage.DELIVERY, SlotFormat.FEED, 5, 10),
            new Slot(4, PostStage.DELIVERY, SlotFormat.REEL, 8, 20));

    private static PlanRequest request(int photos) {
        List<GenerationRequest.Photo> list = new ArrayList<>();
        for (int i = 1; i <= photos; i++) {
            list.add(new GenerationRequest.Photo("photo-" + i, new byte[] {1}));
        }
        return new PlanRequest(new GenerationRequest.Brand(null, null, null, null), "Family", null, list, SLOTS);
    }

    private static PlanResult.Post post(Integer slot, List<String> photos, String caption) {
        return new PlanResult.Post(slot, photos, caption, " Send it on. ", List.of("#a", "b", "c", "d", "e", "f"),
                " Frames in order. ");
    }

    @Test
    void keepsOnePostPerSlotWithCleanPhotosAndText() {
        ValidatedPlan plan = PlanValidator.validate(new PlanResult(List.of(
                        post(1, List.of("photo-1", "photo-9", "photo-1", "photo-2", "photo-3", "photo-4"), " Peek "),
                        post(1, List.of("photo-5"), "A second answer for slot 1"),
                        post(2, List.of("photo-1"), "Story"),
                        post(3, List.of("photo-1", "photo-4", "photo-5"), "Delivery"),
                        post(4, List.of("photo-1", "photo-2"), "Reel"),
                        post(9, List.of("photo-1"), "Unknown slot")),
                List.of(" Check the venue. ", ""), 10, 20), request(5));

        assertThat(plan.posts()).extracting(PlannedPost::format)
                .containsExactly(PostFormat.CAROUSEL, PostFormat.STORY, PostFormat.CAROUSEL, PostFormat.REEL);
        PlannedPost peek = plan.posts().get(0);
        assertThat(peek.photos()).as("unknown and repeated photos dropped, capped at the slot's 3").containsExactly(0, 1, 2);
        assertThat(peek.text()).isEqualTo(new DraftText("Peek", "Send it on.", List.of("a", "b", "c", "d", "e")));
        assertThat(peek.idea()).as("feed posts carry no Reel notes").isNull();
        assertThat(plan.posts().get(1).text().hashtags()).as("a Story has no hashtags").isEmpty();
        assertThat(plan.posts().get(1).idea()).isEqualTo("Frames in order.");
        assertThat(plan.posts().get(2).photos()).as("no photo of an earlier feed post").containsExactly(3, 4);
        assertThat(plan.posts().get(3).photos()).as("a Reel may reuse photos").containsExactly(0, 1);
        assertThat(plan.warnings()).containsExactly("Check the venue.");
    }

    @Test
    void dropsPostsLeftWithoutPhotosOrCaption() {
        ValidatedPlan plan = PlanValidator.validate(new PlanResult(List.of(
                        post(1, List.of("photo-1"), "Peek"),
                        post(2, List.of("photo-7"), "No known photo"),
                        post(3, List.of("photo-1"), "Only a photo the sneak peek used"),
                        post(4, List.of("photo-1"), "  ")),
                List.of(), null, null), request(2));

        assertThat(plan.posts()).singleElement().satisfies(p -> {
            assertThat(p.format()).isEqualTo(PostFormat.SINGLE);
            assertThat(p.slot().number()).isEqualTo(1);
        });
    }

    @Test
    void anAnswerWithoutAUsablePostIsInvalid() {
        assertThatThrownBy(() -> PlanValidator.validate(new PlanResult(List.of(), List.of(), null, null), request(1)))
                .isInstanceOfSatisfying(ContentGenerationException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INVALID_OUTPUT));
        assertThatThrownBy(() -> PlanValidator.validate(null, request(1))).isInstanceOf(ContentGenerationException.class);
    }
}
