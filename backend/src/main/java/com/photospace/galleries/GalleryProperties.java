package com.photospace.galleries;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Gallery limits.
 *
 * @param maxPhotos the most photos one gallery may hold
 * @param maxLinkDays the longest a share link may stay valid, in days
 * @param downloadUrlValidity how long a presigned download URL handed to a visitor works
 */
@Validated
@ConfigurationProperties("app.galleries")
public record GalleryProperties(
        @Min(1) @Max(2000) int maxPhotos, @Min(1) @Max(3650) int maxLinkDays, @NotNull Duration downloadUrlValidity) {}
