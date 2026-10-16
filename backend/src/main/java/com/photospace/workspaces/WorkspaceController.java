package com.photospace.workspaces;

import com.photospace.identity.SignedInUser;
import com.photospace.workspaces.WorkspaceService.MemberView;
import com.photospace.workspaces.WorkspaceService.WorkspaceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/workspaces} lists and creates the caller's workspaces. Everything under
 * {@code /api/workspaces/{workspaceId}} is checked by {@link WorkspaceAccessInterceptor} before it runs and
 * receives the verified {@link WorkspaceContext}. The path variable itself is never used for data access.
 */
@RestController
class WorkspaceController {

    private final WorkspaceService workspaces;

    WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/api/workspaces")
    List<WorkspaceView> list(@AuthenticationPrincipal SignedInUser user) {
        return workspaces.listForUser(user.userId());
    }

    @PostMapping("/api/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    WorkspaceView create(@AuthenticationPrincipal SignedInUser user, @Valid @RequestBody WorkspaceRequest request) {
        return workspaces.create(user.userId(), request.name(), request.timezone());
    }

    @GetMapping("/api/workspaces/{workspaceId}")
    WorkspaceView get(WorkspaceContext workspace) {
        return workspaces.get(workspace);
    }

    @PutMapping("/api/workspaces/{workspaceId}")
    WorkspaceView update(WorkspaceContext workspace, @Valid @RequestBody WorkspaceRequest request) {
        return workspaces.update(workspace, request.name(), request.timezone());
    }

    @GetMapping("/api/workspaces/{workspaceId}/members")
    List<MemberView> members(WorkspaceContext workspace) {
        return workspaces.listMembers(workspace);
    }

    record WorkspaceRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @IanaTimezone String timezone) {}
}
