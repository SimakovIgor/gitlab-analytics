package io.simakov.analytics.security;

import io.simakov.analytics.BaseIT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for WorkspaceContextFilter session-lifecycle behaviour.
 *
 * <p>Covers three critical scenarios:
 * <ul>
 *   <li>Valid session with matching workspace → request proceeds normally (200)</li>
 *   <li>Stale session with deleted/non-existent workspaceId → session invalidated, redirect /login</li>
 *   <li>Authenticated user with no workspaceId in session → redirect /onboarding</li>
 *   <li>/onboarding is exempt from workspace check → filter passes it through unconditionally</li>
 * </ul>
 */
class WorkspaceContextFilterTest extends BaseIT {

    @Test
    void validSessionWithExistingWorkspace_proceedsNormally() throws Exception {
        // webSession contains SESSION_WORKSPACE_ID = testWorkspaceId (set in BaseIT.setUpWorkspace).
        // WorkspaceContextFilter must set WorkspaceContext and let the request through.
        // /settings returns 200 with an existing workspace even when no projects exist.
        mockMvc.perform(get("/settings")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk());
    }

    @Test
    void staleSession_workspaceDeletedFromDb_redirectsToLogin() throws Exception {
        // Session references a workspace that no longer exists in the database.
        // WorkspaceContextFilter must invalidate the session and redirect to /login.
        MockHttpSession staleSession = new MockHttpSession();
        staleSession.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, Long.MAX_VALUE);

        mockMvc.perform(get("/report")
                .session(staleSession)
                .with(ownerPrincipal()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));

        // Session must be invalidated so the stale workspaceId cannot be reused.
        assertThat(staleSession.isInvalid()).isTrue();
    }

    @Test
    void authenticatedUserWithNoWorkspaceInSession_redirectsToOnboarding() throws Exception {
        // User authenticated but has no workspace yet (e.g. just registered).
        // WorkspaceContextFilter should send them to /onboarding.
        MockHttpSession emptySession = new MockHttpSession();

        mockMvc.perform(get("/report")
                .session(emptySession)
                .with(ownerPrincipal()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/onboarding"));
    }

    @Test
    void exemptPath_onboarding_proceedsWithoutWorkspace() throws Exception {
        // /onboarding is in EXEMPT_PATHS — filter must not redirect even without a workspace.
        // The controller renders the onboarding page (workspaceReady=false) with status 200.
        MockHttpSession emptySession = new MockHttpSession();

        mockMvc.perform(get("/onboarding")
                .session(emptySession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk());
    }
}
