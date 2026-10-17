package com.photospace.inquiries;

import com.photospace.inquiries.InquiryFormService.Created;
import com.photospace.inquiries.InquiryFormService.FormView;
import com.photospace.workspaces.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workspace's General Inquiry form: create it from the template, edit the draft, publish, unpublish. Any
 * member may do all of these. The workspace interceptor has already checked membership for every route.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/inquiry-form")
class InquiryFormController {

    private final InquiryFormService forms;

    InquiryFormController(InquiryFormService forms) {
        this.forms = forms;
    }

    /** 404 until the form is created. */
    @GetMapping
    FormView get(WorkspaceContext workspace) {
        return forms.get(workspace);
    }

    /** Creates the form from the default template: 201, or 200 with the existing form. */
    @PostMapping
    ResponseEntity<FormView> create(WorkspaceContext workspace) {
        Created result = forms.create(workspace);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.form());
    }

    @PutMapping
    FormView updateDraft(WorkspaceContext workspace, @Valid @RequestBody DraftRequest request) {
        return forms.updateDraft(workspace, request.version(), request.draft());
    }

    @PostMapping("/publish")
    FormView publish(WorkspaceContext workspace, @Valid @RequestBody VersionRequest request) {
        return forms.publish(workspace, request.version());
    }

    @PostMapping("/unpublish")
    FormView unpublish(WorkspaceContext workspace) {
        return forms.unpublish(workspace);
    }

    record DraftRequest(@NotNull Integer version, @NotNull FormConfig draft) {}

    record VersionRequest(@NotNull Integer version) {}
}
