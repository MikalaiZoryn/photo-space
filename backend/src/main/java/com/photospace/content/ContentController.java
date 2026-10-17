package com.photospace.content;

import com.photospace.content.BrandProfileService.BrandProfileView;
import com.photospace.content.ContentService.BatchDetail;
import com.photospace.content.ContentService.BatchView;
import com.photospace.content.ContentService.DraftDetail;
import com.photospace.content.ContentService.DraftView;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The content studio: brand profile, generation batches, and drafts. The workspace interceptor has already
 * checked membership for every route. Nothing here publishes anything; approval only marks a draft ready.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/content")
class ContentController {

    private final ContentService content;
    private final BrandProfileService brands;
    private final ContentGenerationProvider provider;
    private final ContentProperties properties;

    ContentController(ContentService content, BrandProfileService brands, ContentGenerationProvider provider,
            ContentProperties properties) {
        this.content = content;
        this.brands = brands;
        this.provider = provider;
        this.properties = properties;
    }

    /** What the generator is and its limits, so the UI can say where photos go before anything is sent. */
    @GetMapping("/settings")
    SettingsView settings(WorkspaceContext workspace) {
        return new SettingsView(provider.provider(), provider.model(), properties.maxPhotos(), 5, ContentPrompt.VERSION);
    }

    @GetMapping("/brand-profile")
    BrandProfileView brandProfile(WorkspaceContext workspace) {
        return brands.get(workspace);
    }

    @PutMapping("/brand-profile")
    BrandProfileView updateBrandProfile(WorkspaceContext workspace, @Valid @RequestBody BrandProfileRequest request) {
        return brands.update(workspace, request.version(), request.profile());
    }

    @GetMapping("/batches")
    PageView<BatchView> listBatches(
            WorkspaceContext workspace,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return content.list(workspace, sessionId, new PageQuery(page, size));
    }

    /** Queues generation; the batch is {@code PENDING} until the job finishes. Poll the batch for the result. */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    BatchDetail createBatch(WorkspaceContext workspace, @Valid @RequestBody BatchInput request) {
        return content.create(workspace, request);
    }

    @GetMapping("/batches/{batchId}")
    BatchDetail getBatch(WorkspaceContext workspace, @PathVariable UUID batchId) {
        return content.get(workspace, batchId);
    }

    @PostMapping("/batches/{batchId}/retry")
    BatchDetail retryBatch(WorkspaceContext workspace, @PathVariable UUID batchId) {
        return content.retry(workspace, batchId);
    }

    @PostMapping("/batches/{batchId}/regenerate")
    @ResponseStatus(HttpStatus.CREATED)
    BatchDetail regenerateBatch(WorkspaceContext workspace, @PathVariable UUID batchId) {
        return content.regenerate(workspace, batchId);
    }

    @GetMapping("/drafts/{draftId}")
    DraftDetail getDraft(WorkspaceContext workspace, @PathVariable UUID draftId) {
        return content.getDraft(workspace, draftId);
    }

    @PutMapping("/drafts/{draftId}")
    DraftView updateDraft(WorkspaceContext workspace, @PathVariable UUID draftId,
            @Valid @RequestBody DraftRequest request) {
        return content.updateDraft(workspace, draftId, request.version(), request.draft());
    }

    @PostMapping("/drafts/{draftId}/status")
    DraftView changeDraftStatus(WorkspaceContext workspace, @PathVariable UUID draftId,
            @Valid @RequestBody DraftStatusRequest request) {
        return content.changeDraftStatus(workspace, draftId, request.version(), request.status());
    }

    record SettingsView(String provider, String model, int maxPhotos, int maxCaptions, String promptVersion) {}

    record BrandProfileRequest(@NotNull Integer version, @Valid @NotNull BrandProfileInput profile) {}

    record DraftRequest(@NotNull Integer version, @Valid @NotNull DraftInput draft) {}

    /** The {@code version} is the one the person reviewed; approval applies to exactly that text. */
    record DraftStatusRequest(@NotNull Integer version, @NotNull DraftStatus status) {}
}
