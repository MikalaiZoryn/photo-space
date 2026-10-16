package com.photospace.jobs;

/**
 * Runs one type of job. The worker may deliver the same job more than once (retries, duplicate messages), so
 * {@link #run} must be idempotent: check the subject's current state and write results under fixed keys.
 *
 * <p>An exception from {@link #run} means "try again later". When the attempts run out, {@link #giveUp} runs
 * once so the handler can record a final, user-visible outcome. A failure the handler knows is permanent (an
 * invalid upload, say) is an outcome, not an exception: record it and return normally.
 */
public interface JobHandler {

    String type();

    void run(Job job) throws Exception;

    default void giveUp(Job job) {}
}
