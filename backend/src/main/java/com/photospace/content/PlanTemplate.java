package com.photospace.content;

import com.photospace.content.PlanRequest.Slot;
import com.photospace.content.PlanRequest.SlotFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The posts a plan asks for and when each one goes out, from the workspace's settings. Deterministic: the model
 * fills the slots but never chooses their stage, format, photo counts, or dates.
 *
 * <p>The run follows the research behind Milestone 13: a sneak peek and a Story teaser soon after the photos are
 * ready; a story carousel, a Reel, and a Story on the day the gallery is published; then drip posts spaced over
 * the following weeks.
 */
final class PlanTemplate {

    /** Days after the gallery is published that the Reel goes out. */
    static final int REEL_OFFSET_DAYS = 2;

    private PlanTemplate() {}

    /** A slot with the day it goes out, counted from its stage's anchor. */
    record PlannedSlot(Slot slot, int offsetDays) {}

    static List<PlannedSlot> slots(StudioSettings settings) {
        List<PlannedSlot> slots = new ArrayList<>();
        add(slots, PostStage.SNEAK_PEEK, SlotFormat.FEED, 1, 3, settings.sneakPeekDays());
        add(slots, PostStage.SNEAK_PEEK, SlotFormat.STORY, 1, 3, settings.sneakPeekDays());
        add(slots, PostStage.DELIVERY, SlotFormat.FEED, 5, 10, 0);
        add(slots, PostStage.DELIVERY, SlotFormat.REEL, 8, 20, REEL_OFFSET_DAYS);
        add(slots, PostStage.DELIVERY, SlotFormat.STORY, 1, 5, 0);
        for (int i = 1; i <= settings.dripPosts(); i++) {
            add(slots, PostStage.DRIP, SlotFormat.FEED, 1, 5, i * settings.dripSpacingDays());
        }
        return List.copyOf(slots);
    }

    private static void add(List<PlannedSlot> slots, PostStage stage, SlotFormat format, int min, int max, int offset) {
        slots.add(new PlannedSlot(new Slot(slots.size() + 1, stage, format, min, max), offset));
    }
}
