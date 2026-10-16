package com.photospace.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqsJobQueueTest {

    @Test
    void readsTheJobIdFromAWellFormedBodyOnly() {
        UUID id = UUID.randomUUID();
        assertThat(SqsJobQueue.jobId("{\"jobId\":\"" + id + "\"}")).isEqualTo(id);
        assertThat(SqsJobQueue.jobId(" {\"jobId\":\"" + id + "\"}\n")).isEqualTo(id);

        assertThat(SqsJobQueue.jobId(null)).isNull();
        assertThat(SqsJobQueue.jobId("")).isNull();
        assertThat(SqsJobQueue.jobId(id.toString())).isNull();
        assertThat(SqsJobQueue.jobId("{\"jobId\":\"not-a-uuid-but-36-characters-long-xxxx\"}")).isNull();
        assertThat(SqsJobQueue.jobId("{\"jobId\":\"" + id + "\",\"extra\":1}")).isNull();
    }
}
