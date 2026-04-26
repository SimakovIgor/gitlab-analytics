package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InsightsControllerTest extends BaseIT {

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    @Autowired
    private MergeRequestRepository mergeRequestRepository;

    private Long projectId;

    @BeforeEach
    void setUpProject() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl")
            .baseUrl("https://git.example.com")
            .build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("test-token")
            .enabled(true)
            .build());

        projectId = project.getId();
    }

    @Test
    void insightsPage_returnsOkForAuthenticatedUser() throws Exception {
        MvcResult result = mockMvc.perform(get("/insights")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Инсайты");
        assertThat(html).contains("Алгоритмические");
        assertThat(html).contains("AI-рекомендации");
    }

    @Test
    void insightsPage_redirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/insights"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void insightsPage_showsEmptyStateWhenNoUsersConfigured() throws Exception {
        // No users → InsightService returns empty list → empty state
        MvcResult result = mockMvc.perform(get("/insights?period=LAST_30_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Всё в порядке");
    }

    @Test
    void insightsPage_detectsStuckMr() throws Exception {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .email("dev@example.com")
            .displayName("Test Dev")
            .build());

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .gitlabUserId(42L)
            .email("dev@example.com")
            .build());

        // MR open for 48 hours → should trigger STUCK_MRS
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(1001L)
            .gitlabMrIid(1L)
            .state(MrState.OPENED)
            .title("Stuck MR")
            .authorGitlabUserId(42L)
            .createdAtGitlab(Instant.now().minus(48, ChronoUnit.HOURS))
            .additions(10)
            .deletions(5)
            .changesCount(15)
            .filesChangedCount(2)
            .build());

        MvcResult result = mockMvc.perform(get("/insights?period=LAST_30_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("STUCK_MRS");
        assertThat(html).contains("открытыми больше");
    }

    @Test
    void insightsPage_respectsPeriodParameter() throws Exception {
        MvcResult result = mockMvc.perform(get("/insights?period=LAST_7_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("period-seg-btn-active");
    }
}
