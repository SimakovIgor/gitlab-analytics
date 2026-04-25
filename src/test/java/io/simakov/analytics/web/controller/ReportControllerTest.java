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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReportControllerTest extends BaseIT {

    private static final long ALICE_GITLAB_ID = 101L;
    private static final long BOB_GITLAB_ID = 202L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private MockMvc mockMvc;

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
    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl").baseUrl("https://git.example.com").build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());
        projectId = project.getId();

        TrackedUser alice = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice").email("alice@example.com").enabled(true).build());
        aliceId = alice.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(aliceId).gitlabUserId(ALICE_GITLAB_ID)
            .email("alice@example.com").username("alice").build());

        TrackedUser bob = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Bob").email("bob@example.com").enabled(true).build());
        bobId = bob.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(bobId).gitlabUserId(BOB_GITLAB_ID)
            .email("bob@example.com").username("bob").build());
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    @Test
    void reportPageReturns200WithAuth() throws Exception {
        mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    @Test
    void reportPageRedirectsToLoginWithoutAuth() throws Exception {
        mockMvc.perform(get("/report"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    // ── Summary block ─────────────────────────────────────────────────────────

    @Test
    void reportPageRendersSummaryBlock() throws Exception {
        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .contains("summary-grid")
            .contains("MR смерджено")
            .contains("Активных разработчиков")
            .contains("Медиана до мержа")
            .contains("Комментарии к ревью");
    }

    @Test
    void summaryTotalMrMergedMatchesTableData() throws Exception {
        saveMergedMr(1L, ALICE_GITLAB_ID);
        saveMergedMr(2L, ALICE_GITLAB_ID);
        saveMergedMr(3L, BOB_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER"))
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        // Summary should show total of 3 merged MRs
        assertThat(body).contains("summary-value").contains(">3<");
    }

    // ── showInactive filter ───────────────────────────────────────────────────

    @Test
    void allUsersShownByDefault() throws Exception {
        // Alice has a merged MR (active), Bob has nothing (inactive)
        saveMergedMr(1L, ALICE_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER"))
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        // Both users appear by default (showInactive=true is the default)
        assertThat(body)
            .contains("class=\"user-name\">Alice")
            .contains("class=\"user-name\">Bob");
    }

    @Test
    void inactiveUsersHiddenWhenShowInactiveFalse() throws Exception {
        // Alice has a merged MR, Bob has nothing
        saveMergedMr(1L, ALICE_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER"))
                .param("period", "LAST_30_DAYS")
                .param("showInactive", "false"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("class=\"user-name\">Alice");
        assertThat(body).doesNotContain("class=\"user-name\">Bob");
    }

    @Test
    void summaryActiveDeveloperCountReflectsOnlyActiveUsers() throws Exception {
        // Only Alice has activity
        saveMergedMr(1L, ALICE_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER"))
                .param("period", "LAST_30_DAYS"))
            .andExpect(status().isOk())
            .andReturn();

        // activeDevs/totalDevs — 1 active out of 2 total
        assertThat(result.getResponse().getContentAsString())
            .contains("/ 2");
    }

    // ── projectIds filter ─────────────────────────────────────────────────────

    @Test
    void reportPageRendersProjectCheckboxes() throws Exception {
        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("repo");
    }

    @Test
    void reportFilterByUnknownProjectIdReturnsNoData() throws Exception {
        saveMergedMr(1L, ALICE_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER"))
                .param("period", "LAST_30_DAYS")
                .param("projectIds", "99999")
                .param("showInactive", "false"))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Нет данных за выбранный период");
    }

    // ── Period filter ─────────────────────────────────────────────────────────

    @Test
    void reportPageShowsNoDataForEmptyPeriod() throws Exception {
        // MR merged 60 days ago — outside LAST_30_DAYS
        MergeRequest old = new MergeRequest();
        old.setTrackedProjectId(projectId);
        old.setGitlabMrId(5L);
        old.setGitlabMrIid(5L);
        old.setState(MrState.MERGED);
        old.setCreatedAtGitlab(NOW.minus(62, ChronoUnit.DAYS));
        old.setMergedAtGitlab(NOW.minus(61, ChronoUnit.DAYS));
        old.setAuthorGitlabUserId(ALICE_GITLAB_ID);
        mergeRequestRepository.save(old);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER"))
                .param("period", "LAST_30_DAYS")
                .param("showInactive", "false"))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .contains("Нет данных за выбранный период");
    }

    @Test
    void reportPageShowsDeltaBaselineNote() throws Exception {
        saveMergedMr(1L, ALICE_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .contains("к пред.");
    }

    // ── Metric selector ───────────────────────────────────────────────────────

    @Test
    void reportPageRendersChartMetricSelector() throws Exception {
        saveMergedMr(1L, ALICE_GITLAB_ID);

        MvcResult result = mockMvc.perform(get("/report").session(webSession).with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(result.getResponse().getContentAsString())
            .contains("chartMetricSeg")
            .contains("mr_merged_count");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveMergedMr(Long gitlabMrId,
                              long authorGitlabUserId) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(gitlabMrId);
        mr.setGitlabMrIid(gitlabMrId);
        mr.setState(MrState.MERGED);
        mr.setCreatedAtGitlab(NOW.minus(5, ChronoUnit.DAYS));
        mr.setMergedAtGitlab(NOW.minus(1, ChronoUnit.DAYS));
        mr.setAuthorGitlabUserId(authorGitlabUserId);
        mergeRequestRepository.save(mr);
    }
}
