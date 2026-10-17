package com.photospace.content;

/** {@code PENDING} covers queued, running, and waiting for an automatic retry. */
enum ContentBatchStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
