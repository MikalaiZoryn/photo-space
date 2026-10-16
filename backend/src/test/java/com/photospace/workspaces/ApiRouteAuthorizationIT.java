package com.photospace.workspaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A guard for future modules: every API route must be either on the short list below or under
 * {@code /api/workspaces/{workspaceId}}, where the central interceptor checks membership. Workspace routes must
 * also take the verified {@link WorkspaceContext} rather than reading the ID themselves.
 */
@IntegrationTest
class ApiRouteAuthorizationIT {

    /** Routes that are public or scoped to the signed-in user rather than to a workspace. */
    private static final Set<String> NON_WORKSPACE_ROUTES = Set.of(
            "/api/health", // public
            "/api/auth/options", // public
            "/api/auth/csrf", // public
            "/api/me", // the signed-in user
            "/api/workspaces", // the signed-in user's memberships
            "/api/dev/login", // test classpath only
            "/error");

    private static final String WORKSPACE_PREFIX = "/api/workspaces/{workspaceId}";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyRouteIsPublicUserScopedOrWorkspaceScoped() {
        List<String> violations = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            for (String pattern : info.getPatternValues()) {
                boolean workspaceRoute = pattern.equals(WORKSPACE_PREFIX) || pattern.startsWith(WORKSPACE_PREFIX + "/");
                if (workspaceRoute) {
                    boolean takesContext = Arrays.asList(method.getMethod().getParameterTypes())
                            .contains(WorkspaceContext.class);
                    if (!takesContext) {
                        violations.add(pattern + " does not take a WorkspaceContext: " + method);
                    }
                } else if (!NON_WORKSPACE_ROUTES.contains(pattern)) {
                    violations.add(pattern + " is neither allow-listed nor under " + WORKSPACE_PREFIX + ": " + method);
                }
            }
        });

        assertThat(violations).isEmpty();
        assertThat(handlerMapping.getHandlerMethods()).isNotEmpty();
    }
}
