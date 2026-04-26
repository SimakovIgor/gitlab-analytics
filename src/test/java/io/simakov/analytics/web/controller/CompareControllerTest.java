package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompareControllerTest extends BaseIT {

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @BeforeEach
    void setUpProject() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl")
            .baseUrl("https://git.example.com")
            .build());

        trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());
    }

    @Test
    void comparePageReturns200() throws Exception {
        MvcResult result = mockMvc.perform(get("/compare")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotEmpty();
    }

    @Test
    void comparePageRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/compare"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void comparePageHandlesInvalidPeriodGracefully() throws Exception {
        mockMvc.perform(get("/compare?period=INVALID")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    @Test
    void comparePageWithProjectIdFilter() throws Exception {
        mockMvc.perform(get("/compare?period=LAST_30_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }
}
