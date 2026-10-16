package com.photospace.storage;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where photos are stored. {@code endpoint} is set only for a local S3-compatible emulator; deployed
 * environments leave it empty and use AWS with the task role's credentials.
 */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(@NotBlank String bucket, @NotBlank String region, URI endpoint) {

    boolean usesEmulator() {
        return endpoint != null && !endpoint.toString().isBlank();
    }
}
