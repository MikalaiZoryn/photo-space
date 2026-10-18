package com.photospace.content;

import com.photospace.assets.Crop;
import com.photospace.content.ContentPlanService.PlanDetail;
import com.photospace.content.ContentPlanService.PlanView;
import com.photospace.content.ContentPostService.PostDetail;
import com.photospace.content.ContentPostService.PostFilter;
import com.photospace.content.ContentPostService.PostKit;
import com.photospace.content.ContentPostService.PostPhotoInput;
import com.photospace.content.ContentPostService.PostView;
import com.photospace.shared.Text;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.shared.web.InvalidFieldsException;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The Content Studio (Milestone 13): its settings, session plans, and planned posts with their post kit. The
 * workspace interceptor has already checked membership for every route. Nothing here posts anything: approval
 * marks a post ready, and "posted" is what a person records after posting by hand.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/content")
class ContentStudioController {

    /** The widest calendar range one request may ask for. */
    static final int MAX_RANGE_DAYS = 62;

    /** A posted link is shown as a link, so only web links are kept. */
    private static final Pattern POST_URL = Pattern.compile("https?://\\S+");

    private final StudioSettingsService settings;
    private final ContentPlanService plans;
    private final ContentPostService posts;

    ContentStudioController(StudioSettingsService settings, ContentPlanService plans, ContentPostService posts) {
        this.settings = settings;
        this.plans = plans;
        this.posts = posts;
    }

    @GetMapping("/studio-settings")
    StudioSettings studioSettings(WorkspaceContext workspace) {
        return settings.get(workspace);
    }

    @PutMapping("/studio-settings")
    StudioSettings updateStudioSettings(WorkspaceContext workspace, @Valid @RequestBody StudioSettingsRequest request) {
        return settings.update(workspace, request.version(), request.settings());
    }

    /** Plans, most recently changed first; {@code toReview} keeps those with posts to approve or a problem. */
    @GetMapping("/plans")
    PageView<PlanView> listPlans(
            WorkspaceContext workspace,
            @RequestParam(defaultValue = "false") boolean toReview,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return plans.list(workspace, toReview, new PageQuery(page, size));
    }

    @GetMapping("/sessions/{sessionId}/plan")
    PlanDetail getPlan(WorkspaceContext workspace, @PathVariable UUID sessionId) {
        return plans.get(workspace, sessionId);
    }

    /** Creates the session's plan or refreshes it; the plan is {@code PENDING} until its job finishes. */
    @PostMapping("/sessions/{sessionId}/plan")
    PlanDetail requestPlan(WorkspaceContext workspace, @PathVariable UUID sessionId) {
        return plans.request(workspace, sessionId);
    }

    @PostMapping("/plans/{planId}/retry")
    PlanDetail retryPlan(WorkspaceContext workspace, @PathVariable UUID planId) {
        return plans.retry(workspace, planId);
    }

    /** Adds a post a person wrote to the plan. */
    @PostMapping("/plans/{planId}/posts")
    @ResponseStatus(HttpStatus.CREATED)
    PostDetail addPost(WorkspaceContext workspace, @PathVariable UUID planId, @Valid @RequestBody PostInput request) {
        PlanView plan = plans.find(workspace, planId);
        return posts.add(workspace, plan.id(), plan.sessionId(), plan.batchId(), request);
    }

    /**
     * Posts in calendar order. {@code from} and {@code to} (inclusive, at most {@value #MAX_RANGE_DAYS} days
     * apart) keep posts planned or posted in the range; {@code unscheduled} keeps approved posts without a day.
     */
    @GetMapping("/posts")
    PageView<PostView> listPosts(
            WorkspaceContext workspace,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PostStatus status,
            @RequestParam(defaultValue = "false") boolean unscheduled,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        if (from != null && to != null && (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to))) {
            throw InvalidFieldsException.of("to", "must be on or after from, and at most " + MAX_RANGE_DAYS + " days later");
        }
        return posts.list(workspace, new PostFilter(sessionId, from, to, status, unscheduled), new PageQuery(page, size));
    }

    @GetMapping("/posts/{postId}")
    PostDetail getPost(WorkspaceContext workspace, @PathVariable UUID postId) {
        return posts.get(workspace, postId);
    }

    @PutMapping("/posts/{postId}/schedule")
    PostView schedulePost(WorkspaceContext workspace, @PathVariable UUID postId,
            @Valid @RequestBody ScheduleRequest request) {
        return posts.schedule(workspace, postId, request.version(), request.plannedOn());
    }

    @PutMapping("/posts/{postId}/photos")
    PostDetail updatePostPhotos(WorkspaceContext workspace, @PathVariable UUID postId,
            @Valid @RequestBody PhotosRequest request) {
        return posts.updatePhotos(workspace, postId, request.version(), request.photos().stream()
                .map(photo -> new PostPhotoInput(photo.assetId(), photo.crop() == null ? null : photo.crop().toCrop()))
                .toList());
    }

    @PostMapping("/posts/{postId}/posted")
    PostView markPosted(WorkspaceContext workspace, @PathVariable UUID postId,
            @Valid @RequestBody PostedRequest request) {
        String postUrl = Text.optional(request.postUrl());
        if (postUrl != null && !POST_URL.matcher(postUrl).matches()) {
            throw InvalidFieldsException.of("postUrl", "must be a link that starts with https://");
        }
        return posts.markPosted(workspace, postId, request.version(), request.posted(), request.postedOn(), postUrl);
    }

    @PostMapping("/posts/{postId}/regenerate")
    PostView regeneratePost(WorkspaceContext workspace, @PathVariable UUID postId,
            @Valid @RequestBody VersionRequest request) {
        return posts.regenerate(workspace, postId, request.version());
    }

    /** The approved post's text and download links for each photo's 4:5 export and original. */
    @GetMapping("/posts/{postId}/kit")
    PostKit postKit(WorkspaceContext workspace, @PathVariable UUID postId) {
        return posts.kit(workspace, postId);
    }

    @DeleteMapping("/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePost(WorkspaceContext workspace, @PathVariable UUID postId, @RequestParam int version) {
        posts.delete(workspace, postId, version);
    }

    record StudioSettingsRequest(@NotNull Integer version, @Valid @NotNull StudioSettingsInput settings) {}

    record VersionRequest(@NotNull Integer version) {}

    /** {@code plannedOn} null clears the day. */
    record ScheduleRequest(@NotNull Integer version, LocalDate plannedOn) {}

    record PhotosRequest(@NotNull Integer version, @NotNull @Size(max = 20) List<@Valid @NotNull PhotoRequest> photos) {}

    /** A crop is optional; none means the centred default. */
    record PhotoRequest(@NotNull UUID assetId, @Valid CropRequest crop) {}

    record CropRequest(@NotNull Double x, @NotNull Double y, @NotNull Double width, @NotNull Double height) {

        Crop toCrop() {
            return new Crop(x, y, width, height);
        }
    }

    record PostedRequest(
            @NotNull Integer version, @NotNull Boolean posted, LocalDate postedOn, @Size(max = 500) String postUrl) {}
}
