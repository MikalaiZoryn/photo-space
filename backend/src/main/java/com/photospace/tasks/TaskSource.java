package com.photospace.tasks;

/** Who created a task: a person, an automation rule, or an inquiry's review or follow-up step. */
public enum TaskSource {
    MANUAL,
    AUTOMATION,
    INQUIRY
}
