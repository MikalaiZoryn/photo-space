package com.photospace.jobs;

import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workspace's background jobs, read-only, for owners. Recovery is per feature (re-upload a photo, retry a
 * content batch or an automation run), because only the feature knows how to redo its work safely.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/jobs")
class JobController {

    private final JobService jobs;

    JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    PageView<JobView> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) JobStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        workspace.requireOwner();
        return jobs.list(workspace.workspaceId(), status, new PageQuery(page, size));
    }
}
