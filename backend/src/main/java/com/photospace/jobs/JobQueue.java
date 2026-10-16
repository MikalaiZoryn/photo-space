package com.photospace.jobs;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The message queue that wakes workers. A message carries only a job ID; the job row holds everything else.
 * Delivery is at least once, so the same ID can arrive twice.
 */
interface JobQueue {

    void send(UUID jobId);

    /** Up to {@code max} messages, waiting up to {@code wait} for the first. */
    List<Message> receive(int max, Duration wait);

    /** Removes a handled message. */
    void delete(Message message);

    /** {@code jobId} is null when the body is not a valid job message. */
    record Message(String receiptHandle, UUID jobId) {}
}
