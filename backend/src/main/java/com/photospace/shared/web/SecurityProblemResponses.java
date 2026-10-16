package com.photospace.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Problem bodies for 401 and 403 responses that Spring Security writes from its filters, before MVC's
 * exception handling can run. The body matches {@link ApiExceptionHandler}'s shape.
 */
public final class SecurityProblemResponses {

    private SecurityProblemResponses() {}

    public static AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> write(response, HttpStatus.UNAUTHORIZED);
    }

    public static AccessDeniedHandler forbidden() {
        return (request, response, e) -> write(response, HttpStatus.FORBIDDEN);
    }

    private static void write(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // The correlation ID is generated server-side or matched against a strict pattern, so it is JSON-safe.
        String correlationId = CorrelationIdFilter.current();
        response.getWriter().write("{\"status\":%d,\"title\":\"%s\",\"correlationId\":%s}".formatted(
                status.value(), status.getReasonPhrase(), correlationId == null ? "null" : '"' + correlationId + '"'));
    }
}
