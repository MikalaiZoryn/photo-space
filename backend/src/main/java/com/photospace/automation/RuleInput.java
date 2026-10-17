package com.photospace.automation;

import com.photospace.sessions.SessionStatus;
import com.photospace.shared.Text;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A rule's settings: when a session changes to {@code triggerStatus}, create a task titled {@code taskTitle},
 * due {@code dueInDays} days after the change (in the workspace time zone). Any other property is ignored.
 */
record RuleInput(
        @NotNull SessionStatus triggerStatus,
        @NotBlank @Size(max = 200) String taskTitle,
        @NotNull @Min(0) @Max(365) Integer dueInDays,
        @NotNull Boolean enabled) {

    RuleInput {
        taskTitle = Text.strip(taskTitle);
    }
}
