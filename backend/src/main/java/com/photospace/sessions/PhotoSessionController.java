package com.photospace.sessions;

import com.photospace.sessions.PhotoSessionService.SessionView;
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
 * Photography session CRUD, status changes, and archiving. The workspace interceptor has already checked
 * membership for every route.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/sessions")
class PhotoSessionController {

    private final PhotoSessionService sessions;

    PhotoSessionController(PhotoSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    PageView<SessionView> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "false") boolean upcoming,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return sessions.list(workspace, status, clientId, archived, upcoming, new PageQuery(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionView create(WorkspaceContext workspace, @Valid @RequestBody SessionInput input) {
        return sessions.create(workspace, input);
    }

    @GetMapping("/{sessionId}")
    SessionView get(WorkspaceContext workspace, @PathVariable UUID sessionId) {
        return sessions.get(workspace, sessionId);
    }

    @PutMapping("/{sessionId}")
    SessionView update(
            WorkspaceContext workspace, @PathVariable UUID sessionId, @Valid @RequestBody UpdateRequest request) {
        return sessions.update(workspace, sessionId, request.version(), request.session());
    }

    @PostMapping("/{sessionId}/status")
    SessionView changeStatus(
            WorkspaceContext workspace, @PathVariable UUID sessionId, @Valid @RequestBody StatusRequest request) {
        return sessions.changeStatus(workspace, sessionId, request.status());
    }

    @PostMapping("/{sessionId}/archive")
    SessionView archive(WorkspaceContext workspace, @PathVariable UUID sessionId) {
        return sessions.archive(workspace, sessionId);
    }

    @PostMapping("/{sessionId}/restore")
    SessionView restore(WorkspaceContext workspace, @PathVariable UUID sessionId) {
        return sessions.restore(workspace, sessionId);
    }

    /** The session fields plus the {@code version} the caller last saw, for optimistic locking. */
    record UpdateRequest(@NotNull Integer version, @Valid @NotNull SessionInput session) {}

    record StatusRequest(@NotNull SessionStatus status) {}
}
