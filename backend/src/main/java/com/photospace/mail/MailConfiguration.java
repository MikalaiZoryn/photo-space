package com.photospace.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/** Picks the email provider. Only the {@code ses} provider builds an AWS client. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties.class)
class MailConfiguration {

    @Bean
    EmailSender emailSender(MailProperties properties) {
        return switch (properties.provider()) {
            case FILE -> new FileEmailSender(properties.fileDirectory(), sender(properties, "Photo Space <no-reply@localhost>"));
            case SES -> new SesEmailSender(sesClient(properties), sender(properties, null));
            case DISABLED -> message -> {
                throw new EmailDeliveryException("Email sending is not configured", true, null);
            };
        };
    }

    private static String sender(MailProperties properties, String fallback) {
        if (properties.from() != null && !properties.from().isBlank()) {
            return EmailMessage.singleLine(properties.from()).strip();
        }
        if (fallback == null) {
            throw new IllegalStateException("app.mail.from (MAIL_FROM) is required with the ses provider");
        }
        return fallback;
    }

    private static SesV2Client sesClient(MailProperties properties) {
        return SesV2Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .apiCallTimeout(Duration.ofSeconds(90)))
                .build();
    }
}
