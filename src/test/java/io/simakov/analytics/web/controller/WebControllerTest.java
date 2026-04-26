package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebControllerTest extends BaseIT {

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    private Long trackedUserId;

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

        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice")
            .email("alice@example.com")
            .enabled(true)
            .build());

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .gitlabUserId(42L)
            .email("alice@example.com")
            .build());

        trackedUserId = user.getId();
    }

    // ── GET / ────────────────────────────────────────────────────────────────

    @Test
    void homeRedirectsToReportWhenAuthenticated() throws Exception {
        mockMvc.perform(get("/")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/report"));
    }

    @Test
    void homeRendersLandingPageWhenUnauthenticated() throws Exception {
        // Without explicit auth, Spring Security context is empty → controller returns "landing"
        mockMvc.perform(get("/"))
            .andExpect(status().isOk());
    }

    // ── GET /login ───────────────────────────────────────────────────────────

    @Test
    void loginPageReturns200() throws Exception {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk());
    }

    // ── GET /dashboard ───────────────────────────────────────────────────────

    @Test
    void dashboardRedirectsToReport() throws Exception {
        mockMvc.perform(get("/dashboard")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/report"));
    }

    // ── GET /sync ────────────────────────────────────────────────────────────

    @Test
    void syncPageReturns200() throws Exception {
        MvcResult result = mockMvc.perform(get("/sync")
                .session(webSession)
                .with(ownerPrincipal()))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotEmpty();
    }

    @Test
    void syncPageRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/sync"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    // ── GET /report/chart ────────────────────────────────────────────────────

    @Test
    void reportChartReturnsJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/report/chart?period=LAST_30_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).startsWith("{").endsWith("}");
    }

    @Test
    void reportChartRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/report/chart"))
            .andExpect(status().is3xxRedirection());
    }

    // ── GET /report/user/{id}/mrs ─────────────────────────────────────────────

    @Test
    void userMrsReturnsEmptyListForTrackedUserWithNoMrs() throws Exception {
        MvcResult result = mockMvc.perform(get("/report/user/" + trackedUserId + "/mrs?period=LAST_30_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void userMrsRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/report/user/1/mrs"))
            .andExpect(status().is3xxRedirection());
    }
}
