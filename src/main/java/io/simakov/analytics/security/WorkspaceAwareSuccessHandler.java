package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceAwareSuccessHandler implements AuthenticationSuccessHandler {

    public static final String SESSION_WORKSPACE_ID = "workspaceId";

    private final WorkspaceMemberRepository workspaceMemberRepository;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        Long appUserId = principal.getAppUser().getId();

        List<WorkspaceMember> memberships = workspaceMemberRepository.findByAppUserId(appUserId);
        HttpSession session = request.getSession();

        if (memberships.isEmpty()) {
            log.info("AppUser id={} has no workspace — showing onboarding on report page", appUserId);
        } else {
            Long workspaceId = memberships.get(0).getWorkspaceId();
            session.setAttribute(SESSION_WORKSPACE_ID, workspaceId);
            log.debug("AppUser id={} logged in, workspaceId={}", appUserId, workspaceId);
        }
        response.sendRedirect("/report");
    }
}
