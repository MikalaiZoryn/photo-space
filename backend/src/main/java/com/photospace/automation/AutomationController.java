package com.photospace.automation;

import com.photospace.automation.AutomationService.RuleView;
import com.photospace.automation.AutomationService.RunView;
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
 * Automation rules and their runs. Members can read both and retry a failed run; only owners create or change
 * rules. The workspace interceptor has already checked membership for every route.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/automation")
class AutomationController {

    private final AutomationService automation;

    AutomationController(AutomationService automation) {
        this.automation = automation;
    }

    @GetMapping("/rules")
    List<RuleView> listRules(WorkspaceContext workspace) {
        return automation.listRules(workspace);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    RuleView createRule(WorkspaceContext workspace, @Valid @RequestBody RuleInput input) {
        return automation.createRule(workspace, input);
    }

    @PutMapping("/rules/{ruleId}")
    RuleView updateRule(WorkspaceContext workspace, @PathVariable UUID ruleId,
            @Valid @RequestBody UpdateRuleRequest request) {
        return automation.updateRule(workspace, ruleId, request.version(), request.rule());
    }

    @GetMapping("/runs")
    PageView<RunView> listRuns(
            WorkspaceContext workspace,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return automation.listRuns(workspace, status, new PageQuery(page, size));
    }

    @PostMapping("/runs/{runId}/retry")
    RunView retryRun(WorkspaceContext workspace, @PathVariable UUID runId) {
        return automation.retryRun(workspace, runId);
    }

    /** The rule settings plus the {@code version} the caller last saw, for optimistic locking. */
    record UpdateRuleRequest(@NotNull Integer version, @Valid @NotNull RuleInput rule) {}
}
