package com.photospace.workspaces;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Installs the workspace authorization interceptor and lets handlers receive the verified {@link WorkspaceContext}. */
@Configuration(proxyBeanMethods = false)
class WorkspaceWebConfig implements WebMvcConfigurer {

    private final WorkspaceService workspaces;

    WorkspaceWebConfig(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WorkspaceAccessInterceptor(workspaces))
                .addPathPatterns(WorkspaceAccessInterceptor.WORKSPACE_PATH);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new WorkspaceContextResolver());
    }

    private static final class WorkspaceContextResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == WorkspaceContext.class;
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest request,
                WebDataBinderFactory binderFactory) {
            Object context = request.getAttribute(
                    WorkspaceAccessInterceptor.CONTEXT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (context == null) {
                // Fail closed: a handler outside /api/workspaces/{workspaceId}/** asked for a workspace.
                throw new IllegalStateException("WorkspaceContext is only available under "
                        + WorkspaceAccessInterceptor.WORKSPACE_PATH);
            }
            return context;
        }
    }
}
