package com.photospace.inquiries;

import com.photospace.inquiries.InquiryService.InquirySummary;
import com.photospace.inquiries.InquiryService.InquiryView;
import com.photospace.shared.paging.PageQuery;
import com.photospace.shared.paging.PageView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inquiries from the public form. The workspace interceptor has already checked membership for every route. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/inquiries")
class InquiryController {

    private final InquiryService inquiries;

    InquiryController(InquiryService inquiries) {
        this.inquiries = inquiries;
    }

    @GetMapping
    PageView<InquirySummary> list(
            WorkspaceContext workspace,
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(defaultValue = "false") boolean open,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageQuery.MAX_PAGE) int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return inquiries.list(workspace, status, open, new PageQuery(page, size));
    }

    @GetMapping("/{inquiryId}")
    InquiryView get(WorkspaceContext workspace, @PathVariable UUID inquiryId) {
        return inquiries.get(workspace, inquiryId);
    }

    /** Links an existing client ({@code clientId}) or creates one from the answers ({@code createClient}). */
    @PostMapping("/{inquiryId}/associate")
    InquiryView associate(WorkspaceContext workspace, @PathVariable UUID inquiryId,
            @Valid @RequestBody AssociateRequest request) {
        return inquiries.associate(workspace, inquiryId, request.version(), request.clientId(),
                Boolean.TRUE.equals(request.createClient()));
    }

    @PostMapping("/{inquiryId}/dismiss")
    InquiryView dismiss(WorkspaceContext workspace, @PathVariable UUID inquiryId, @Valid @RequestBody VersionRequest request) {
        return inquiries.dismiss(workspace, inquiryId, request.version());
    }

    @PostMapping("/{inquiryId}/reopen")
    InquiryView reopen(WorkspaceContext workspace, @PathVariable UUID inquiryId, @Valid @RequestBody VersionRequest request) {
        return inquiries.reopen(workspace, inquiryId, request.version());
    }

    record AssociateRequest(@NotNull Integer version, UUID clientId, Boolean createClient) {}

    record VersionRequest(@NotNull Integer version) {}
}
