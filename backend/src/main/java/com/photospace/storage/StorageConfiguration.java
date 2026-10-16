package com.photospace.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 client and presigner. Against AWS they use the default credential chain (the ECS task role in
 * deployed environments). Against a local emulator they use path-style URLs and placeholder credentials,
 * because emulators accept any.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(s3Configuration(properties))
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(60)))
                // Bounded calls: an originals download is at most the upload size limit.
                .overrideConfiguration(override -> override
                        .apiCallAttemptTimeout(Duration.ofMinutes(2))
                        .apiCallTimeout(Duration.ofMinutes(5)));
        if (properties.usesEmulator()) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties properties) {
        var builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(s3Configuration(properties));
        if (properties.usesEmulator()) {
            builder.endpointOverride(properties.endpoint());
        }
        return builder.build();
    }

    @Bean
    ObjectStorage objectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        return new S3ObjectStorage(s3, presigner, properties.bucket());
    }

    private static S3Configuration s3Configuration(StorageProperties properties) {
        return S3Configuration.builder().pathStyleAccessEnabled(properties.usesEmulator()).build();
    }

    private static AwsCredentialsProvider credentials(StorageProperties properties) {
        return properties.usesEmulator()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create("local-emulator", "local-emulator"))
                : DefaultCredentialsProvider.builder().build();
    }
}
