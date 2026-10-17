package com.photospace.dashboard;

import com.photospace.dashboard.DashboardLayoutService.LayoutView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in member's dashboard layout in a workspace. The membership is verified for every route, and the
 * layout belongs to that member: no route takes a user ID.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/dashboard/layout")
class DashboardController {

    private final DashboardLayoutService layouts;

    DashboardController(DashboardLayoutService layouts) {
        this.layouts = layouts;
    }

    @GetMapping
    LayoutView get(WorkspaceContext workspace) {
        return layouts.get(workspace);
    }

    @PutMapping
    LayoutView update(WorkspaceContext workspace, @Valid @RequestBody LayoutRequest request) {
        return layouts.update(workspace, request.version(), request.widgets());
    }

    /** Back to the default layout. Answers with it, so the page needs no second request. */
    @DeleteMapping
    LayoutView reset(WorkspaceContext workspace) {
        return layouts.reset(workspace);
    }

    record LayoutRequest(@NotNull Integer version, @NotNull List<@Valid @NotNull WidgetInput> widgets) {}
}
