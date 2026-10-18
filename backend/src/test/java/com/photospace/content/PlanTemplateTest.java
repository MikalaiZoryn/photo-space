package com.photospace.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.content.PlanRequest.SlotFormat;
import com.photospace.content.PlanTemplate.PlannedSlot;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTemplateTest {

    @Test
    void followsTheSettingsForTheSneakPeekAndDripPosts() {
        List<PlannedSlot> slots = PlanTemplate.slots(new StudioSettings(4, true, 2, 3, 5, null, 0, null));

        assertThat(slots).extracting(s -> s.slot().stage() + " " + s.slot().format() + " +" + s.offsetDays())
                .containsExactly(
                        "SNEAK_PEEK FEED +2",
                        "SNEAK_PEEK STORY +2",
                        "DELIVERY FEED +0",
                        "DELIVERY REEL +2",
                        "DELIVERY STORY +0",
                        "DRIP FEED +5",
                        "DRIP FEED +10",
                        "DRIP FEED +15");
        assertThat(slots).extracting(s -> s.slot().number()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(slots.get(2).slot()).satisfies(s -> {
            assertThat(s.minPhotos()).isEqualTo(5);
            assertThat(s.maxPhotos()).isEqualTo(10);
        });
    }

    @Test
    void theDefaultsAskForElevenPosts() {
        List<PlannedSlot> slots = PlanTemplate.slots(StudioSettings.DEFAULTS);

        assertThat(slots).hasSize(11);
        assertThat(slots).filteredOn(s -> s.slot().format() == SlotFormat.FEED && s.slot().stage() == PostStage.DRIP)
                .extracting(PlannedSlot::offsetDays)
                .containsExactly(7, 14, 21, 28, 35, 42);
    }

    @Test
    void spreadsPhotosOverTheWholeSession() {
        assertThat(ContentPlanService.spread(List.of(1, 2, 3), 5)).containsExactly(1, 2, 3);
        assertThat(ContentPlanService.spread(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), 4)).containsExactly(0, 3, 6, 9);
    }
}
