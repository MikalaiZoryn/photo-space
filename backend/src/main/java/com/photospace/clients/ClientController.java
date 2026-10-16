package com.photospace.clients;

import com.photospace.clients.ClientService.ClientView;
import com.photospace.shared.Text;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** Client CRUD and archiving. The workspace interceptor has already checked membership for every route. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/clients")
class ClientController {

    private final ClientService clients;

    ClientController(ClientService clients) {
        this.clients = clients;
    }

    @GetMapping
    PageView<ClientView> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return clients.list(workspace, Text.optional(q), archived, new PageQuery(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ClientView create(WorkspaceContext workspace, @Valid @RequestBody ClientInput input) {
        return clients.create(workspace, input);
    }

    @GetMapping("/{clientId}")
    ClientView get(WorkspaceContext workspace, @PathVariable UUID clientId) {
        return clients.get(workspace, clientId);
    }

    @PutMapping("/{clientId}")
    ClientView update(WorkspaceContext workspace, @PathVariable UUID clientId, @Valid @RequestBody UpdateRequest request) {
        return clients.update(workspace, clientId, request.version(), request.client());
    }

    @PostMapping("/{clientId}/archive")
    ClientView archive(WorkspaceContext workspace, @PathVariable UUID clientId) {
        return clients.archive(workspace, clientId);
    }

    @PostMapping("/{clientId}/restore")
    ClientView restore(WorkspaceContext workspace, @PathVariable UUID clientId) {
        return clients.restore(workspace, clientId);
    }

    /** The client fields plus the {@code version} the caller last saw, for optimistic locking. */
    record UpdateRequest(@NotNull Integer version, @Valid @NotNull ClientInput client) {}
}
