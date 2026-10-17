package com.photospace.tasks;

import com.photospace.shared.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The editable fields of a task. {@code dueDate} is a calendar date in the workspace time zone. The client,
 * session, and assignee are checked against the workspace. Status is not editable here: it changes only through
 * the status endpoint. Any other property in the request body is ignored.
 */
record TaskInput(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 10_000) String description,
        LocalDate dueDate,
        UUID clientId,
        UUID sessionId,
        UUID assigneeId) {

    TaskInput {
        title = Text.strip(title);
        description = Text.optional(description);
    }
}
