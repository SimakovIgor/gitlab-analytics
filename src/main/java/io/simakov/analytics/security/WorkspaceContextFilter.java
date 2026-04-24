package io.simakov.analytics.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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
 * If the session references a workspace that no longer exists (e.g. after a
 * dev DB reset), the session is invalidated and the user is sent to /login.
 * API requests are handled by BearerTokenAuthFilter.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class WorkspaceContextFilter extends OncePerRequestFilter {

    private static final Set<String> EXEMPT_PATHS = Set.of("/onboarding", "/login", "/logout", "/join");
    private static final List<String> EXEMPT_PREFIXES = List.of("/oauth2/", "/css/", "/js/", "/actuator/");

    private final WorkspaceRepository workspaceRepository;

    private static boolean requiresWorkspace(String uri) {
        return !EXEMPT_PATHS.contains(uri)
            && EXEMPT_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

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

        String redirectTo = resolveRedirect(session, workspaceId, request.getRequestURI());
        if (redirectTo != null) {
            response.sendRedirect(redirectTo);
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }

    /**
     * Returns a redirect path if the request cannot proceed, or {@code null} if it can.
     * Side effects: sets {@link WorkspaceContext} or invalidates a stale session.
     */
    private String resolveRedirect(HttpSession session,
                                   Long workspaceId,
                                   String uri) {
        if (workspaceId != null) {
            if (workspaceRepository.existsById(workspaceId)) {
                WorkspaceContext.set(workspaceId);
                return null;
            }
            // Stale session — workspace deleted (e.g. dev DB reset). Force re-login.
            session.invalidate();
            return "/login";
        }
        if (!WorkspaceContext.isSet() && requiresWorkspace(uri) && isAuthenticated()) {
            return "/onboarding";
        }
        return null;
    }
}
