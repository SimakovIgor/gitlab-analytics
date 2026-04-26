package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.web.controller.JoinController;
import io.simakov.analytics.workspace.InviteService;
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
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceAwareSuccessHandler implements AuthenticationSuccessHandler {

    public static final String SESSION_WORKSPACE_ID = "workspaceId";

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final InviteService inviteService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        Long appUserId = principal.getAppUser().getId();
        HttpSession session = request.getSession();

        // Consume pending invite if user arrived via /join link
        String pendingToken = (String) session.getAttribute(JoinController.SESSION_PENDING_INVITE);
        if (pendingToken != null) {
            session.removeAttribute(JoinController.SESSION_PENDING_INVITE);
            Optional<WorkspaceInvite> invite = inviteService.findValid(pendingToken);
            if (invite.isPresent()) {
                inviteService.consumeInvite(invite.get(), appUserId);
                session.setAttribute(SESSION_WORKSPACE_ID, invite.get().getWorkspaceId());
                log.info("AppUser id={} joined workspace={} via invite after OAuth", appUserId, invite.get().getWorkspaceId());
                response.sendRedirect("/report");
                return;
            }
            log.warn("AppUser id={} had pending invite token but it was invalid/expired", appUserId);
        }

        List<WorkspaceMember> memberships = workspaceMemberRepository.findByAppUserId(appUserId);

        if (memberships.isEmpty()) {
            log.info("AppUser id={} has no workspace — redirecting to onboarding", appUserId);
            // Clear any stale workspace from a previous session so WorkspaceContextFilter
            // does not restore it and skip the workspace-creation step in onboarding.
            session.removeAttribute(SESSION_WORKSPACE_ID);
            response.sendRedirect("/onboarding");
        } else {
            Long workspaceId = memberships.get(0).getWorkspaceId();
            session.setAttribute(SESSION_WORKSPACE_ID, workspaceId);
            log.debug("AppUser id={} logged in, workspaceId={}", appUserId, workspaceId);
            response.sendRedirect("/report");
        }
    }
}
