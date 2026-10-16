package com.photospace.assets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What the browser declares about a file before uploading it. None of it is trusted: the type and size are
 * enforced by the signed upload URL and verified again by processing, and the name is only displayed.
 */
record UploadInput(
        @NotBlank @Size(max = 255) String filename,
        @NotBlank @Size(max = 100) String contentType,
        @NotNull @Positive Long byteSize) {}
