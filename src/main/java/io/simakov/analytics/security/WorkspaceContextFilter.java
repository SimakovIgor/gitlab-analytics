package io.simakov.analytics.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Sets WorkspaceContext from HTTP session for web (non-API) requests.
 * Authenticated users without a workspace are redirected to /onboarding.
 * API requests are handled by BearerTokenAuthFilter.
 */
@Component
@Order(10)
public class WorkspaceContextFilter extends OncePerRequestFilter {

    private static final Set<String> EXEMPT_PATHS = Set.of("/onboarding", "/login", "/logout");
    private static final List<String> EXEMPT_PREFIXES = List.of("/oauth2/", "/css/", "/js/", "/actuator/");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Long workspaceId = session == null
            ? null
            : (Long) session.getAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID);

        if (workspaceId != null) {
            WorkspaceContext.set(workspaceId);
        } else if (requiresWorkspace(request.getRequestURI()) && isAuthenticated()) {
            response.sendRedirect("/onboarding");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }

    private static boolean requiresWorkspace(String uri) {
        return !EXEMPT_PATHS.contains(uri)
            && EXEMPT_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
