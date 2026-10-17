package com.photospace.invitations;

import com.photospace.identity.SignedInUser;
import com.photospace.invitations.InvitationAcceptance.InvitationPreview;
import com.photospace.invitations.InvitationService.InvitationView;
import com.photospace.workspaces.WorkspaceContext;
import com.photospace.workspaces.WorkspaceService.WorkspaceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owners manage invitations under {@code /api/workspaces/{workspaceId}/invitations}. The invited person reads and
 * accepts theirs under {@code /api/invitation}, signed in, with the token from the email in the
 * {@value #TOKEN_HEADER} header: never in a URL, so it stays out of access logs.
 */
@RestController
class InvitationController {

    static final String TOKEN_HEADER = "X-Invitation-Token";

    private final InvitationService invitations;
    private final InvitationAcceptance acceptance;

    InvitationController(InvitationService invitations, InvitationAcceptance acceptance) {
        this.invitations = invitations;
        this.acceptance = acceptance;
    }

    @GetMapping("/api/workspaces/{workspaceId}/invitations")
    List<InvitationView> list(WorkspaceContext workspace) {
        return invitations.listPending(workspace);
    }

    @PostMapping("/api/workspaces/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationView create(WorkspaceContext workspace, @Valid @RequestBody InvitationRequest request) {
        return invitations.create(workspace, request.email());
    }

    @PostMapping("/api/workspaces/{workspaceId}/invitations/{invitationId}/resend")
    InvitationView resend(WorkspaceContext workspace, @PathVariable UUID invitationId) {
        return invitations.resend(workspace, invitationId);
    }

    @PostMapping("/api/workspaces/{workspaceId}/invitations/{invitationId}/revoke")
    InvitationView revoke(WorkspaceContext workspace, @PathVariable UUID invitationId) {
        return invitations.revoke(workspace, invitationId);
    }

    @GetMapping("/api/invitation")
    InvitationPreview preview(
            @AuthenticationPrincipal SignedInUser user, @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return acceptance.preview(user.userId(), token);
    }

    @PostMapping("/api/invitation/accept")
    WorkspaceView accept(
            @AuthenticationPrincipal SignedInUser user, @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        return acceptance.accept(user.userId(), token);
    }

    record InvitationRequest(@NotBlank @Email @Size(max = 254) String email) {}
}
