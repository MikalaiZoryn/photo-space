package com.photospace.content;

import com.photospace.shared.Text;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A request for drafts: selected ready photos of one session, in order, the notes to send (the photographer
 * chooses them; the session's own notes are not sent unless copied here), and what to write.
 */
record BatchInput(
        @NotNull UUID sessionId,
        @NotNull List<@NotNull UUID> assetIds,
        @Size(max = 4000) String notes,
        @NotNull @Min(1) @Max(5) Integer captionCount,
        @NotNull Boolean includeHashtags,
        @NotNull Boolean includeCallToAction) {

    BatchInput {
        notes = Text.optional(notes);
    }
}
