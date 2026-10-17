package com.photospace.audit;

import com.photospace.audit.AuditService.AuditEventView;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The workspace audit log, read-only. Owners only; members get 403. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/audit-events")
class AuditController {

    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    PageView<AuditEventView> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return audit.list(workspace, targetId, new PageQuery(page, size));
    }
}
