package com.photospace.branding;

import com.photospace.assets.AssetService;
import com.photospace.assets.AssetService.UploadView;
import com.photospace.branding.BrandingService.BrandingView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workspace's branding for its public inquiry pages. Any member may change it. A logo is uploaded like a
 * photo: start the upload here, {@code PUT} the file to the returned URL, complete it through the assets API, and
 * save the branding with its asset ID once it is {@code READY}.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/branding")
class BrandingController {

    private final BrandingService branding;
    private final AssetService assets;

    BrandingController(BrandingService branding, AssetService assets) {
        this.branding = branding;
        this.assets = assets;
    }

    @GetMapping
    BrandingView get(WorkspaceContext workspace) {
        return branding.get(workspace);
    }

    @PutMapping
    BrandingView update(WorkspaceContext workspace, @Valid @RequestBody UpdateRequest request) {
        return branding.update(workspace, request.version(), request.branding());
    }

    @PostMapping("/logo-uploads")
    @ResponseStatus(HttpStatus.CREATED)
    UploadView startLogoUpload(WorkspaceContext workspace, @Valid @RequestBody LogoUpload request) {
        return assets.createLogoUpload(workspace, request.filename(), request.contentType(), request.byteSize());
    }

    record UpdateRequest(@NotNull Integer version, @Valid @NotNull BrandingInput branding) {}

    record LogoUpload(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank @Size(max = 100) String contentType,
            @NotNull @Positive Long byteSize) {}
}
