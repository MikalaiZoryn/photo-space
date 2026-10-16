package com.photospace.workspaces;

import com.photospace.identity.SignedInUser;
import com.photospace.shared.web.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The central workspace authorization check. It runs for every handler under
 * {@value #WORKSPACE_PATH} and fails closed: a missing or malformed ID, an unknown workspace, and a workspace
 * the user does not belong to all produce the same 404, so other tenants' IDs reveal nothing.
 */
class WorkspaceAccessInterceptor implements HandlerInterceptor {

    static final String WORKSPACE_PATH = "/api/workspaces/{workspaceId}/**";
    static final String CONTEXT_ATTRIBUTE = WorkspaceContext.class.getName();

    private final WorkspaceService workspaces;

    WorkspaceAccessInterceptor(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID userId = currentUserId();
        UUID workspaceId = workspaceIdFromPath(request);
        WorkspaceRole role = workspaces.findRole(workspaceId, userId).orElseThrow(NotFoundException::new);
        request.setAttribute(CONTEXT_ATTRIBUTE, new WorkspaceContext(workspaceId, userId, role));
        return true;
    }

    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SignedInUser user) {
            return user.userId();
        }
        // Spring Security admits only signed-in users to these paths, so this is a configuration bug.
        throw new IllegalStateException("Workspace API reached without a signed-in user");
    }

    private static UUID workspaceIdFromPath(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = variables == null ? null : variables.get("workspaceId");
        if (raw == null) {
            throw new NotFoundException();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }
}
