package com.photospace.content;

import com.photospace.shared.Text;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A post a person adds to a session's plan: its photos in order, its text, and optionally a day and Reel notes. */
record PostInput(
        @NotNull PostStage stage,
        @NotNull PostFormat format,
        @NotNull List<@NotNull UUID> photos,
        @Valid @NotNull DraftInput draft,
        @Size(max = 1000) String idea,
        LocalDate plannedOn) {

    PostInput {
        idea = Text.optional(idea);
    }
}
