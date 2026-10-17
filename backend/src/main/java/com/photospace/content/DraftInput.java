package com.photospace.content;

import com.photospace.shared.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** An edited draft. Hashtags may be sent with or without the # sign. */
record DraftInput(
        @NotBlank @Size(max = 2200) String caption,
        @Size(max = 300) String callToAction,
        @NotNull @Size(max = 30) List<String> hashtags) {

    DraftInput {
        caption = Text.strip(caption);
        callToAction = Text.optional(callToAction);
        hashtags = hashtags == null ? null : hashtags.stream().map(Hashtags::clean).toList();
    }
}
