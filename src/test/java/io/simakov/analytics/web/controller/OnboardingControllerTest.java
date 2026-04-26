package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OnboardingControllerTest extends BaseIT {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void onboardingPageReturns200WithWorkspaceReady() throws Exception {
        // webSession already has workspaceId set (from BaseIT) but no projects/users
        // so onboarding mode is active
        MvcResult result = mockMvc.perform(get("/onboarding")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotEmpty();
    }

    @Test
    void onboardingPageWithoutWorkspaceSessionShowsNoWorkspace() throws Exception {
        MockHttpSession sessionWithoutWorkspace = new MockHttpSession();
        // no workspace ID in session → workspaceReady=false branch
        MvcResult result = mockMvc.perform(get("/onboarding")
                .session(sessionWithoutWorkspace)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotEmpty();
    }

    @Test
    void onboardingPageRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/onboarding"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void postOnboardingCreatesWorkspaceAndRedirects() throws Exception {
        long workspaceCountBefore = workspaceRepository.count();

        mockMvc.perform(post("/onboarding")
                .with(ownerPrincipal())
                .with(csrf())
                .param("workspaceName", "My New Workspace"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/onboarding"));

        assertThat(workspaceRepository.count()).isGreaterThan(workspaceCountBefore);
    }

    @Test
    void postOnboardingRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/onboarding")
                .with(csrf())
                .param("workspaceName", "test"))
            .andExpect(status().is3xxRedirection());
    }
}
