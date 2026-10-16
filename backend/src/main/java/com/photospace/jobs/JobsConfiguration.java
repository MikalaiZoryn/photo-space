package com.photospace.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Builds the SQS client. Against AWS it uses the default credential chain (the ECS task role); against a local
 * emulator, placeholder credentials.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProperties.class)
class JobsConfiguration {

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(JobProperties properties) {
        // A receive long-polls for up to waitTime, so each attempt may take that long plus the network.
        Duration attemptTimeout = properties.worker().waitTime().plusSeconds(10);
        var builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(properties.usesEmulator()
                        ? StaticCredentialsProvider.create(AwsBasicCredentials.create("local-emulator", "local-emulator"))
                        : DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(attemptTimeout))
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(attemptTimeout)
                        .apiCallTimeout(attemptTimeout.multipliedBy(3)));
        if (properties.usesEmulator()) {
            builder.endpointOverride(properties.sqsEndpoint());
        }
        return builder.build();
    }

    @Bean
    JobQueue jobQueue(SqsClient sqs, JobProperties properties) {
        return new SqsJobQueue(sqs, properties.queueUrl());
    }
}
