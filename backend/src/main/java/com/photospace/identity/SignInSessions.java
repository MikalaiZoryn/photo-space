package com.photospace.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Establishes the application session for a signed-in user. Whatever authentication the provider produced is
 * replaced by a {@link SignedInUser}, so ID tokens and provider claims never reach the session store.
 */
@Component
public class SignInSessions {

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public void establish(SignedInUser user, HttpServletRequest request, HttpServletResponse response) {
        // Session fixation protection: never reuse a session ID that existed before sign-in.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }

    SecurityContextRepository repository() {
        return repository;
    }
}
