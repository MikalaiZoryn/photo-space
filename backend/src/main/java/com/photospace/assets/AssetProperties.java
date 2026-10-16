package com.photospace.assets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Upload limits and processing settings.
 *
 * @param maxUploadBytes the largest original accepted
 * @param workspaceQuotaBytes total original bytes a workspace may hold, counting uploads in progress
 * @param maxPendingUploads uploads a workspace may have started but not finished
 * @param uploadUrlValidity how long a presigned upload URL works
 * @param viewUrlValidity how long a presigned thumbnail or preview URL works
 * @param maxPixels the largest image (width × height) processing will decode
 * @param previewSize the long edge of the preview, in pixels
 * @param thumbnailSize the long edge of the thumbnail, in pixels
 */
@Validated
@ConfigurationProperties("app.assets")
public record AssetProperties(
        @Min(1) long maxUploadBytes,
        @Min(1) long workspaceQuotaBytes,
        @Min(1) @Max(10_000) int maxPendingUploads,
        @NotNull Duration uploadUrlValidity,
        @NotNull Duration viewUrlValidity,
        @Min(1) long maxPixels,
        @Min(64) @Max(8192) int previewSize,
        @Min(32) @Max(2048) int thumbnailSize,
        @Valid @NotNull Cleanup cleanup) {

    /** Uploads still pending after {@code abandonedAfter} are deleted, checked every {@code interval}. */
    public record Cleanup(boolean enabled, @NotNull Duration interval, @NotNull Duration abandonedAfter) {}
}
