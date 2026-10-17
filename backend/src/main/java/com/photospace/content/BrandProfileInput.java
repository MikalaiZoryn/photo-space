package com.photospace.content;

import com.photospace.shared.Text;
import jakarta.validation.constraints.Size;

/** The photographer's description of their brand. Every field is optional; blank means not given. */
record BrandProfileInput(
        @Size(max = 1000) String voice,
        @Size(max = 1000) String audience,
        @Size(max = 2000) String preferences,
        @Size(max = 1000) String avoid) {

    BrandProfileInput {
        voice = Text.optional(voice);
        audience = Text.optional(audience);
        preferences = Text.optional(preferences);
        avoid = Text.optional(avoid);
    }
}
