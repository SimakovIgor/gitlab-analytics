package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.web.controller.JoinController;
import io.simakov.analytics.workspace.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for WorkspaceAwareSuccessHandler post-authentication routing.
 *
 * <p>Key regression: when a newly-registered user has no workspace memberships, the handler
 * must clear any stale SESSION_WORKSPACE_ID left over from a previous session before redirecting
 * to /onboarding. Without this, WorkspaceContextFilter would silently restore the stale
 * workspace and skip the workspace-creation step.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceAwareSuccessHandlerTest {

    private static final Long APP_USER_ID = 42L;
    private static final Long WORKSPACE_ID = 7L;

    @Mock
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Authentication authentication;

    @Mock
    private HttpSession session;

    @InjectMocks
    private WorkspaceAwareSuccessHandler handler;

    @BeforeEach
    void setUpSession() {
        when(request.getSession()).thenReturn(session);
        // No pending invite by default
        when(session.getAttribute(JoinController.SESSION_PENDING_INVITE)).thenReturn(null);

        AppUser user = AppUser.builder().id(APP_USER_ID).email("user@test.com").name("User").build();
        AppUserPrincipal principal = new AppUserPrincipal(user);
        when(authentication.getPrincipal()).thenReturn(principal);
    }

    @Test
    void userWithMembership_setsWorkspaceInSession_redirectsToReport() throws IOException {
        WorkspaceMember member = WorkspaceMember.builder()
            .workspaceId(WORKSPACE_ID)
            .appUserId(APP_USER_ID)
            .role("OWNER")
            .build();
        when(workspaceMemberRepository.findByAppUserId(APP_USER_ID)).thenReturn(List.of(member));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(session).setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, WORKSPACE_ID);
        verify(response).sendRedirect("/report");
    }

    @Test
    void userWithNoMembership_redirectsToOnboarding() throws IOException {
        when(workspaceMemberRepository.findByAppUserId(APP_USER_ID)).thenReturn(List.of());

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).sendRedirect("/onboarding");
    }

    @Test
    void userWithNoMembership_clearsStaleWorkspaceIdFromSession() throws IOException {
        // Regression: stale SESSION_WORKSPACE_ID must be removed so WorkspaceContextFilter
        // does not restore it and skip the workspace-creation step in onboarding.
        when(workspaceMemberRepository.findByAppUserId(APP_USER_ID)).thenReturn(List.of());

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(session).removeAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID);
    }

    @Test
    void userWithNoMembership_doesNotSetWorkspaceIdInSession() throws IOException {
        when(workspaceMemberRepository.findByAppUserId(APP_USER_ID)).thenReturn(List.of());

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(session, never()).setAttribute(eq(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID), eq(WORKSPACE_ID));
    }
}
