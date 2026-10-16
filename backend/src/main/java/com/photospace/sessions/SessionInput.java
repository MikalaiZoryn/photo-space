package com.photospace.sessions;

import com.photospace.shared.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The editable fields of a session. Start and end are wall-clock times in the workspace time zone, without an
 * offset (for example {@code 2026-11-07T10:30}); see {@link SessionSchedule}. Status is not editable here: it
 * changes only through the status endpoint. Any other property in the request body is ignored.
 */
record SessionInput(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 100) String sessionType,
        UUID clientId,
        LocalDateTime startLocal,
        LocalDateTime endLocal,
        @Size(max = 300) String location,
        @Size(max = 10_000) String notes) {

    SessionInput {
        title = Text.strip(title);
        sessionType = Text.optional(sessionType);
        location = Text.optional(location);
        notes = Text.optional(notes);
    }
}
