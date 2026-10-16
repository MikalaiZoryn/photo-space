package com.photospace.galleries;

import com.photospace.galleries.GalleryService.GalleryDetail;
import com.photospace.galleries.GalleryService.GalleryView;
import com.photospace.galleries.ShareLinkService.CreatedLink;
import com.photospace.galleries.ShareLinkService.LinkView;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
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
 * Gallery management and share links. The workspace interceptor has already checked membership for every route.
 * A created link's token is in the response once and is never logged.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/galleries")
class GalleryController {

    private final GalleryService galleries;
    private final ShareLinkService links;

    GalleryController(GalleryService galleries, ShareLinkService links) {
        this.galleries = galleries;
        this.links = links;
    }

    @GetMapping
    PageView<GalleryView> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return galleries.list(workspace, sessionId, archived, new PageQuery(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GalleryDetail create(WorkspaceContext workspace, @Valid @RequestBody CreateRequest request) {
        return galleries.create(workspace, request.sessionId(), request.gallery());
    }

    @GetMapping("/{galleryId}")
    GalleryDetail get(WorkspaceContext workspace, @PathVariable UUID galleryId) {
        return galleries.get(workspace, galleryId);
    }

    @PutMapping("/{galleryId}")
    GalleryDetail update(
            WorkspaceContext workspace, @PathVariable UUID galleryId, @Valid @RequestBody UpdateRequest request) {
        return galleries.update(workspace, galleryId, request.version(), request.gallery());
    }

    /** Replaces the gallery's photos and their order. */
    @PutMapping("/{galleryId}/photos")
    GalleryDetail setPhotos(
            WorkspaceContext workspace, @PathVariable UUID galleryId, @Valid @RequestBody PhotosRequest request) {
        return galleries.setPhotos(workspace, galleryId, request.version(), request.assetIds());
    }

    @PostMapping("/{galleryId}/status")
    GalleryDetail changeStatus(
            WorkspaceContext workspace, @PathVariable UUID galleryId, @Valid @RequestBody StatusRequest request) {
        return galleries.changeStatus(workspace, galleryId, request.status());
    }

    @PostMapping("/{galleryId}/archive")
    GalleryDetail archive(WorkspaceContext workspace, @PathVariable UUID galleryId) {
        return galleries.archive(workspace, galleryId);
    }

    @PostMapping("/{galleryId}/restore")
    GalleryDetail restore(WorkspaceContext workspace, @PathVariable UUID galleryId) {
        return galleries.restore(workspace, galleryId);
    }

    @GetMapping("/{galleryId}/links")
    PageView<LinkView> listLinks(
            WorkspaceContext workspace,
            @PathVariable UUID galleryId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return links.list(workspace, galleryId, new PageQuery(page, size));
    }

    /** Creates a share link. The response is the only time its token is available. */
    @PostMapping("/{galleryId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedLink createLink(
            WorkspaceContext workspace, @PathVariable UUID galleryId, @Valid @RequestBody LinkRequest request) {
        return links.create(workspace, galleryId, request.validDays());
    }

    @PostMapping("/{galleryId}/links/{linkId}/revoke")
    LinkView revokeLink(WorkspaceContext workspace, @PathVariable UUID galleryId, @PathVariable UUID linkId) {
        return links.revoke(workspace, galleryId, linkId);
    }

    record CreateRequest(@NotNull UUID sessionId, @Valid @NotNull GalleryInput gallery) {}

    /** The gallery settings plus the {@code version} the caller last saw, for optimistic locking. */
    record UpdateRequest(@NotNull Integer version, @Valid @NotNull GalleryInput gallery) {}

    /** All of the gallery's photos in display order, plus the {@code version} the caller last saw. */
    record PhotosRequest(@NotNull Integer version, @NotNull List<@NotNull UUID> assetIds) {}

    record StatusRequest(@NotNull GalleryStatus status) {}

    record LinkRequest(@NotNull Integer validDays) {}
}
