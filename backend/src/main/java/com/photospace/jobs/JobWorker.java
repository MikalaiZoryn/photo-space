package com.photospace.jobs;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The worker loop: long-polls the queue on one background thread and runs each job. It starts with the
 * application context and stops before the database and queue clients close.
 */
@Component
@ConditionalOnProperty("app.jobs.worker.enabled")
class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final Duration ERROR_PAUSE = Duration.ofSeconds(2);

    private final JobQueue queue;
    private final JobRunner runner;
    private final JobProperties.Worker settings;
    private volatile boolean running;
    private Thread thread;

    JobWorker(JobQueue queue, JobRunner runner, JobProperties properties) {
        this.queue = queue;
        this.runner = runner;
        this.settings = properties.worker();
    }

    @Override
    public synchronized void start() {
        running = true;
        thread = Thread.ofPlatform().name("job-worker").daemon().start(this::loop);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                // The current receive returns within waitTime; a running job is left to finish.
                thread.join(settings.waitTime().plusSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                List<JobQueue.Message> messages = queue.receive(settings.batchSize(), settings.waitTime());
                for (JobQueue.Message message : messages) {
                    handle(message);
                }
            } catch (RuntimeException e) {
                log.warn("Job queue poll failed: {}", e.getClass().getSimpleName());
                pause();
            }
        }
    }

    private void handle(JobQueue.Message message) {
        if (message.jobId() == null) {
            log.warn("Deleting a job message with an unreadable body");
        } else {
            // Throws only if the database is unreachable. The message then stays on the queue and comes back
            // after its visibility timeout; repeated failures move it to the dead-letter queue.
            runner.run(message.jobId());
        }
        queue.delete(message);
    }

    private void pause() {
        try {
            Thread.sleep(ERROR_PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
