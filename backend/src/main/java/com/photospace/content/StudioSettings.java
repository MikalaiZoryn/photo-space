package com.photospace.content;

import java.time.Instant;

/**
 * The workspace's Content Studio settings. A workspace that never saved them reads {@link #DEFAULTS} at version
 * 0. {@code updatedAt} is null until the first save.
 */
record StudioSettings(
        int weeklyGoal,
        boolean autoPlans,
        int sneakPeekDays,
        int dripPosts,
        int dripSpacingDays,
        String studioArea,
        int version,
        Instant updatedAt) {

    static final StudioSettings DEFAULTS = new StudioSettings(4, true, 1, 6, 7, null, 0, null);
}
