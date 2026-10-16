package com.photospace.jobs;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * {@link JobQueue} on SQS. Message bodies are {@code {"jobId":"<uuid>"}}. The queue's redrive policy moves a
 * message that keeps failing to be handled to the dead-letter queue (see infra/ and local/elasticmq.conf).
 */
class SqsJobQueue implements JobQueue {

    private static final Pattern BODY = Pattern.compile("\\{\"jobId\":\"([0-9a-fA-F-]{36})\"}");

    private final SqsClient sqs;
    private final String queueUrl;

    SqsJobQueue(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void send(UUID jobId) {
        sqs.sendMessage(send -> send.queueUrl(queueUrl).messageBody("{\"jobId\":\"" + jobId + "\"}"));
    }

    @Override
    public List<Message> receive(int max, Duration wait) {
        return sqs.receiveMessage(receive -> receive
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(max)
                        .waitTimeSeconds((int) wait.toSeconds()))
                .messages()
                .stream()
                .map(message -> new Message(message.receiptHandle(), jobId(message.body())))
                .toList();
    }

    @Override
    public void delete(Message message) {
        sqs.deleteMessage(delete -> delete.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    static UUID jobId(String body) {
        Matcher matcher = body == null ? null : BODY.matcher(body.strip());
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
