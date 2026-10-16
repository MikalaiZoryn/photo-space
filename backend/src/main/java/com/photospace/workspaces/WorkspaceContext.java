package com.photospace.workspaces;

import com.photospace.shared.web.ForbiddenException;
import java.util.UUID;

/**
 * The verified workspace of the current request. Only {@link WorkspaceAccessInterceptor} creates one, after
 * checking that the signed-in user is a member. Controllers for workspace-owned resources take it as a
 * parameter and scope every query by {@link #workspaceId()}; they never read a workspace ID from the request.
 */
public record WorkspaceContext(UUID workspaceId, UUID userId, WorkspaceRole role) {

    public void requireOwner() {
        if (role != WorkspaceRole.OWNER) {
            throw new ForbiddenException();
        }
    }
}
