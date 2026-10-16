package com.photospace.assets;

import com.photospace.assets.AssetService.AssetView;
import com.photospace.assets.AssetService.UploadView;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Photo uploads and asset status. The workspace interceptor has already checked membership for every route.
 * Upload URLs in responses are credentials and are never logged.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
class AssetController {

    private final AssetService assets;

    AssetController(AssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/sessions/{sessionId}/assets")
    PageView<AssetView> list(
            WorkspaceContext workspace,
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return assets.listForSession(workspace, sessionId, new PageQuery(page, size));
    }

    /** Starts an upload: returns the pending asset and a presigned URL to {@code PUT} the file to. */
    @PostMapping("/sessions/{sessionId}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    UploadView createUpload(
            WorkspaceContext workspace, @PathVariable UUID sessionId, @Valid @RequestBody UploadInput input) {
        return assets.createUpload(workspace, sessionId, input);
    }

    @GetMapping("/assets/{assetId}")
    AssetView get(WorkspaceContext workspace, @PathVariable UUID assetId) {
        return assets.get(workspace, assetId);
    }

    /** Reports that the upload finished; processing starts. Idempotent. */
    @PostMapping("/assets/{assetId}/complete")
    AssetView complete(WorkspaceContext workspace, @PathVariable UUID assetId) {
        return assets.complete(workspace, assetId);
    }
}
