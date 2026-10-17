package com.photospace.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound email (D20). {@code file} writes each message as an {@code .eml} file under {@code fileDirectory}
 * and sends nothing, for local development. {@code ses} sends through Amazon SES with the default credential
 * chain (the ECS task role) from the verified sender {@code from}. {@code disabled} fails every send.
 */
@Validated
@ConfigurationProperties("app.mail")
public record MailProperties(
        @NotNull Provider provider, String from, @NotBlank String region, @NotNull Path fileDirectory) {

    public enum Provider {
        FILE,
        SES,
        DISABLED
    }
}
