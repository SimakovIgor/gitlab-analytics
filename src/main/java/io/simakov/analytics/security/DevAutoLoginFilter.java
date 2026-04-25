package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dev-only filter: auto-logs in a mock user so the app can be used without
 * real GitHub OAuth2 credentials. Runs before Spring Security's FilterChainProxy
 * (order -100), so the mock context is visible to all security checks.
 *
 * <p>Activated only when the {@code dev} Spring profile is active.
 */
@Slf4j
@Component
@Profile("dev")
@Order(-200)
@RequiredArgsConstructor
public class DevAutoLoginFilter extends OncePerRequestFilter {

    private static final long DEV_GITHUB_ID = 999_999L;
    private static final String DEV_GITHUB_LOGIN = "dev-user";
    private static final String DEV_WORKSPACE_SLUG = "dev-workspace";
    private static final String DEV_WORKSPACE_NAME = "Dev Workspace";
    private static final String DEV_API_TOKEN = "dev-api-token";

    private final AppUserRepository appUserRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    private static AppUserPrincipal buildPrincipal(AppUser user) {
        Map<String, Object> attrs = Map.of(
            "id", user.getGithubId(),
            "login", user.getGithubLogin()
        );
        DefaultOAuth2User oauth2User = new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, "login");
        return new AppUserPrincipal(user, oauth2User);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Let /login, /register and /logout pass through without auto-login so the
        // email+password auth flow can be tested in dev (e.g. after clicking "Выйти").
        boolean isAuthFlow = "/login".equals(uri) || "/register".equals(uri)
            || "/logout".equals(uri);
        if (isAuthFlow) {
            filterChain.doFilter(request, response);
            return;
        }

        // OAuth2 flow won't work with fake credentials — redirect home instead.
        if (uri.startsWith("/oauth2/authorization/")) {
            ensureDevSession(request);
            response.sendRedirect(request.getContextPath() + "/");
            return;
        }

        HttpSession session = request.getSession(false);
        if (session == null
            || session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null) {
            ensureDevSession(request);
        }
        filterChain.doFilter(request, response);
    }

    private void ensureDevSession(HttpServletRequest request) {
        AppUser devUser = findOrCreateDevUser();
        Workspace devWorkspace = findOrCreateDevWorkspace(devUser.getId());
        ensureMembership(devWorkspace.getId(), devUser.getId());

        AppUserPrincipal principal = buildPrincipal(devUser);
        // Must be OAuth2AuthenticationToken — several controllers declare
        // OAuth2AuthenticationToken as a method parameter and Spring MVC
        // will throw IllegalStateException if the type doesn't match.
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(
            principal, principal.getAuthorities(), "github");

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);

        HttpSession newSession = request.getSession(true);
        newSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        newSession.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, devWorkspace.getId());

        SecurityContextHolder.setContext(ctx);
        log.debug("Dev auto-login: user={} workspaceId={}", DEV_GITHUB_LOGIN, devWorkspace.getId());
    }

    private AppUser findOrCreateDevUser() {
        return appUserRepository.findByGithubId(DEV_GITHUB_ID).orElseGet(() -> {
            AppUser user = AppUser.builder()
                .githubId(DEV_GITHUB_ID)
                .githubLogin(DEV_GITHUB_LOGIN)
                .name("Dev User")
                .lastLoginAt(Instant.now())
                .build();
            AppUser saved = appUserRepository.save(user);
            log.info("Created dev AppUser id={}", saved.getId());
            return saved;
        });
    }

    private Workspace findOrCreateDevWorkspace(Long ownerId) {
        return workspaceRepository.findBySlug(DEV_WORKSPACE_SLUG).orElseGet(() -> {
            Workspace ws = Workspace.builder()
                .name(DEV_WORKSPACE_NAME)
                .slug(DEV_WORKSPACE_SLUG)
                .ownerId(ownerId)
                .plan("FREE")
                .apiToken(DEV_API_TOKEN)
                .build();
            Workspace saved = workspaceRepository.save(ws);
            log.info("Created dev Workspace id={}", saved.getId());
            return saved;
        });
    }

    private void ensureMembership(Long workspaceId,
                                  Long appUserId) {
        if (!workspaceMemberRepository.existsByWorkspaceIdAndAppUserId(workspaceId, appUserId)) {
            workspaceMemberRepository.save(WorkspaceMember.builder()
                .workspaceId(workspaceId)
                .appUserId(appUserId)
                .role("OWNER")
                .build());
        }
    }
}
