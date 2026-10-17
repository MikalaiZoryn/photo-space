package com.photospace.mail;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The email sender integration tests use: it keeps every sent message instead of sending it, and throws the
 * failures a test queues up for a recipient, one per call, before sending to them normally again.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEmailSender {

    public static final List<EmailMessage> SENT = new CopyOnWriteArrayList<>();
    /** By recipient: true for a permanent failure, false for one worth retrying. */
    private static final Map<String, Queue<Boolean>> FAILURES = new ConcurrentHashMap<>();

    @Bean
    @Primary
    EmailSender testEmailSender() {
        return message -> {
            Queue<Boolean> failures = FAILURES.get(message.to());
            Boolean permanent = failures == null ? null : failures.poll();
            if (permanent != null) {
                throw new EmailDeliveryException("Test failure", permanent, null);
            }
            SENT.add(message);
        };
    }

    /** Makes the next sends to {@code address} fail, in order. */
    public static void failNext(String address, Boolean... permanent) {
        FAILURES.computeIfAbsent(address, a -> new ConcurrentLinkedQueue<>()).addAll(List.of(permanent));
    }

    /** The messages sent to {@code address}, oldest first. */
    public static List<EmailMessage> sentTo(String address) {
        return SENT.stream().filter(m -> m.to().equals(address)).toList();
    }
}
