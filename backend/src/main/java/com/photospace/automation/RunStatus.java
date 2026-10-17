package com.photospace.automation;

/** A run is pending until its job creates the task, or failed once the job gives up. */
public enum RunStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
