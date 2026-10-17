package com.photospace.tasks;

import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.tasks.TaskService.TaskFilter;
import com.photospace.tasks.TaskService.TaskView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
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

/** Task CRUD and completion. The workspace interceptor has already checked membership for every route. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/tasks")
class TaskController {

    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    PageView<TaskView> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return tasks.list(workspace, new TaskFilter(status, clientId, sessionId, assigneeId, overdue),
                new PageQuery(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TaskView create(WorkspaceContext workspace, @Valid @RequestBody TaskInput input) {
        return tasks.create(workspace, input);
    }

    @GetMapping("/{taskId}")
    TaskView get(WorkspaceContext workspace, @PathVariable UUID taskId) {
        return tasks.get(workspace, taskId);
    }

    @PutMapping("/{taskId}")
    TaskView update(WorkspaceContext workspace, @PathVariable UUID taskId, @Valid @RequestBody UpdateRequest request) {
        return tasks.update(workspace, taskId, request.version(), request.task());
    }

    @PostMapping("/{taskId}/status")
    TaskView changeStatus(WorkspaceContext workspace, @PathVariable UUID taskId,
            @Valid @RequestBody StatusRequest request) {
        return tasks.changeStatus(workspace, taskId, request.status());
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(WorkspaceContext workspace, @PathVariable UUID taskId) {
        tasks.delete(workspace, taskId);
    }

    /** The task fields plus the {@code version} the caller last saw, for optimistic locking. */
    record UpdateRequest(@NotNull Integer version, @Valid @NotNull TaskInput task) {}

    record StatusRequest(@NotNull TaskStatus status) {}
}
