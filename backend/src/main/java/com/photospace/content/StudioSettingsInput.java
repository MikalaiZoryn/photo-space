package com.photospace.content;

import com.photospace.shared.Text;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Content Studio settings as saved from Settings. A blank studio area means none. */
record StudioSettingsInput(
        @NotNull @Min(1) @Max(14) Integer weeklyGoal,
        @NotNull Boolean autoPlans,
        @NotNull @Min(0) @Max(3) Integer sneakPeekDays,
        @NotNull @Min(0) @Max(8) Integer dripPosts,
        @NotNull @Min(1) @Max(30) Integer dripSpacingDays,
        @Size(max = 100) String studioArea) {

    StudioSettingsInput {
        studioArea = Text.optional(studioArea);
    }
}
