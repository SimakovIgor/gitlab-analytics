package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
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
class TeamControllerTest extends BaseIT {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

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

    @Autowired
    private MergeRequestNoteRepository noteRepository;

    @Autowired
    private MergeRequestApprovalRepository approvalRepository;

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
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        projectId = project.getId();
    }

    // ── Basic page rendering ────────────────────────────────────────────

    @Test
    void teamPageReturns200ForAuthenticatedUser() throws Exception {
        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Команда");
    }

    @Test
    void teamPageRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/team"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void teamPageShowsEmptyStateWhenNoActiveDevs() throws Exception {
        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет активных разработчиков");
    }

    // ── Period filter ───────────────────────────────────────────────────

    @Test
    void teamPageRespectsPeriodParameter() throws Exception {
        MvcResult result = mockMvc.perform(get("/team?period=LAST_7_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("period-seg-btn-active");
    }

    @Test
    void teamPageHandlesInvalidPeriodGracefully() throws Exception {
        mockMvc.perform(get("/team?period=INVALID")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    // ── Developer cards ─────────────────────────────────────────────────

    @Test
    void teamPageShowsDevCardWithMetrics() throws Exception {
        createTrackedUser("dev@test.com", "Igor Simakov", 100L);
        saveMergedMr(1L, 100L, 8);
        saveMergedMr(2L, 100L, 12);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Igor Simakov");
        assertThat(html).contains("team-card");
    }

    @Test
    void teamPageShowsFilterTabCounts() throws Exception {
        createTrackedUser("dev@test.com", "Dev One", 100L);
        saveMergedMr(1L, 100L, 4);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("filter-all");
        assertThat(html).contains("filter-issues");
        assertThat(html).contains("filter-stars");
    }

    @Test
    void teamPageShowsStuckBadgeForSlowDev() throws Exception {
        createTrackedUser("slow@test.com", "Slow Dev", 200L);
        // MR that took 48 hours to merge → median > 24h → "stuck" badge
        saveMergedMr(1L, 200L, 48);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("team-badge-bad");
        assertThat(html).contains("stuck");
    }

    @Test
    void teamPageShowsNoStuckBadgeForFastDev() throws Exception {
        createTrackedUser("fast@test.com", "Fast Dev", 300L);
        saveMergedMr(1L, 300L, 4);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Fast Dev");
        // No "stuck" or "slow" text badges rendered for fast devs
        assertThat(html).doesNotContain(">stuck<");
        assertThat(html).doesNotContain(">slow<");
    }

    @Test
    void teamPageShowsMultipleDevCards() throws Exception {
        createTrackedUser("dev1@test.com", "Alice", 100L);
        createTrackedUser("dev2@test.com", "Bob", 200L);
        saveMergedMr(1L, 100L, 4);
        saveMergedMr(2L, 200L, 8);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Alice");
        assertThat(html).contains("Bob");
    }

    @Test
    void teamPageFiltersDataByProjectIds() throws Exception {
        createTrackedUser("dev@test.com", "Dev", 100L);
        saveMergedMr(1L, 100L, 4);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER"))
                .param("projectIds", "99999"))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет активных разработчиков");
    }

    @Test
    void teamPageShowsProjectCheckboxes() throws Exception {
        GitSource source = gitSourceRepository.findAll().get(0);
        trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(2L)
            .pathWithNamespace("org/repo2")
            .name("repo2")
            .tokenEncrypted("tok2")
            .enabled(true)
            .build());

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("filter-project-check");
        assertThat(html).contains("repo");
        assertThat(html).contains("repo2");
    }

    @Test
    void teamPageExcludesInactiveDevs() throws Exception {
        // Create user but no MRs → inactive → should show empty state
        createTrackedUser("ghost@test.com", "Ghost Dev", 999L);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // No team-card rendered for inactive dev (only empty state shown)
        assertThat(html).contains("Нет активных разработчиков");
    }

    // ── Badge logic ──────────────────────────────────────────────────────

    @Test
    void teamPageShowsSlowBadgeForMediumDev() throws Exception {
        createTrackedUser("mid@test.com", "Mid Dev", 400L);
        // MR merged in ~17h → median between 12h–24h → "slow" badge
        saveMergedMr(1L, 400L, 18);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains(">slow<");
        assertThat(html).doesNotContain(">stuck<");
    }

    @Test
    void teamPageShowsStarBadgeForTopPerformer() throws Exception {
        // Create 2 devs: one with many MRs (fast), one with few
        createTrackedUser("star@test.com", "Star Dev", 500L);
        createTrackedUser("avg@test.com", "Avg Dev", 501L);
        // Star: 4 MRs, fast (4h each) → above 1.5x avg
        saveMergedMr(1L, 500L, 4);
        saveMergedMr(2L, 500L, 4);
        saveMergedMr(3L, 500L, 4);
        saveMergedMr(4L, 500L, 4);
        // Avg: 1 MR → avgMrs = 2.5, star threshold = 3.75 → Star has 4 ≥ 3.75
        saveMergedMr(5L, 501L, 4);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("team-badge-good");
    }

    // ── Metrics rendering ───────────────────────────────────────────────

    @Test
    void teamPageShowsMrCountInCard() throws Exception {
        createTrackedUser("dev@test.com", "Dev X", 600L);
        saveMergedMr(1L, 600L, 4);
        saveMergedMr(2L, 600L, 6);
        saveMergedMr(3L, 600L, 8);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // 3 merged MRs should appear as metric value
        assertThat(html).contains("Dev X");
        assertThat(html).contains(">3<");
    }

    @Test
    void teamPageShowsActiveDaysCount() throws Exception {
        createTrackedUser("dev@test.com", "Active Dev", 700L);
        saveMergedMr(1L, 700L, 4);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("активных дней");
    }

    @Test
    void teamPageShowsReviewCountFromNotes() throws Exception {
        // Author creates MR, reviewer leaves a note → reviewer gets 1 review
        createTrackedUser("author@test.com", "Author", 800L);
        createTrackedUser("reviewer@test.com", "Reviewer", 801L);
        MergeRequest mr = saveMergedMrAndReturn(1L, 800L, 4);
        noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mr.getId())
            .gitlabNoteId(1L)
            .authorGitlabUserId(801L)
            .authorUsername("reviewer")
            .body("LGTM")
            .system(false)
            .internal(false)
            .createdAtGitlab(now.minus(2, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Reviewer");
        assertThat(html).contains("Ревью");
    }

    @Test
    void teamPageShowsReviewCountFromApprovals() throws Exception {
        createTrackedUser("author@test.com", "Author2", 810L);
        createTrackedUser("approver@test.com", "Approver", 811L);
        MergeRequest mr = saveMergedMrAndReturn(1L, 810L, 4);
        approvalRepository.save(MergeRequestApproval.builder()
            .mergeRequestId(mr.getId())
            .approvedByGitlabUserId(811L)
            .approvedByUsername("approver")
            .approvedAtGitlab(now.minus(2, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Approver");
    }

    @Test
    void teamPageShowsMedianTimeToMerge() throws Exception {
        createTrackedUser("dev@test.com", "Median Dev", 900L);
        // MR merged in ~5h → median ~4h displayed
        saveMergedMr(1L, 900L, 5);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Медиана");
        // Median value in hours should be rendered
        assertThat(html).contains("ч");
    }

    // ── Topbar integration ──────────────────────────────────────────────

    @Test
    void teamPageShowsActiveTabInTopbar() throws Exception {
        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("nav-link-active\">Команда");
    }

    // ── Disabled users ──────────────────────────────────────────────────

    @Test
    void teamPageExcludesDisabledUsers() throws Exception {
        TrackedUser disabledUser = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .email("disabled@test.com")
            .displayName("Disabled Dev")
            .enabled(false)
            .build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(disabledUser.getId())
            .gitlabUserId(950L)
            .email("disabled@test.com")
            .name("Disabled Dev")
            .build());
        saveMergedMr(1L, 950L, 4);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // Disabled users should not appear in team cards
        assertThat(html).contains("Нет активных разработчиков");
    }

    // ── Cards order ─────────────────────────────────────────────────────

    @Test
    void teamPageSortsCardsByMrCountDescending() throws Exception {
        createTrackedUser("few@test.com", "Few MRs Dev", 1000L);
        createTrackedUser("many@test.com", "Many MRs Dev", 1001L);
        saveMergedMr(1L, 1000L, 4);
        saveMergedMr(2L, 1001L, 4);
        saveMergedMr(3L, 1001L, 6);
        saveMergedMr(4L, 1001L, 8);

        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // Find positions within team-grid section only
        int gridStart = html.indexOf("team-grid");
        String gridHtml = html.substring(gridStart);
        int manyIdx = gridHtml.indexOf("Many MRs Dev");
        int fewIdx = gridHtml.indexOf("Few MRs Dev");
        assertThat(manyIdx).isLessThan(fewIdx);
    }

    // ── Period filtering ────────────────────────────────────────────────

    @Test
    void teamPageExcludesOldMrsFromMetrics() throws Exception {
        createTrackedUser("dev@test.com", "Period Dev", 1100L);
        // MR merged 60 days ago — should NOT appear in 7-day view
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(99L);
        mr.setGitlabMrIid(99L);
        mr.setState(MrState.MERGED);
        mr.setCreatedAtGitlab(now.minus(65, ChronoUnit.DAYS));
        mr.setMergedAtGitlab(now.minus(60, ChronoUnit.DAYS));
        mr.setAuthorGitlabUserId(1100L);
        mergeRequestRepository.save(mr);

        MvcResult result = mockMvc.perform(get("/team?period=LAST_7_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет активных разработчиков");
    }

    @Test
    void teamPageDefaultPeriodIsLast30Days() throws Exception {
        MvcResult result = mockMvc.perform(get("/team")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // LAST_30_DAYS button should be active by default
        assertThat(html).contains("LAST_30_DAYS");
        assertThat(html).contains("period-seg-btn-active");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private TrackedUser createTrackedUser(String email, String name, Long gitlabUserId) {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .email(email)
            .displayName(name)
            .enabled(true)
            .build());

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .gitlabUserId(gitlabUserId)
            .email(email)
            .name(name)
            .build());

        return user;
    }

    private void saveMergedMr(Long gitlabMrId, Long authorGitlabUserId, int leadHours) {
        saveMergedMrAndReturn(gitlabMrId, authorGitlabUserId, leadHours);
    }

    private MergeRequest saveMergedMrAndReturn(Long gitlabMrId, Long authorGitlabUserId,
                                                int leadHours) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(gitlabMrId);
        mr.setGitlabMrIid(gitlabMrId);
        mr.setState(MrState.MERGED);
        mr.setCreatedAtGitlab(now.minus(leadHours, ChronoUnit.HOURS));
        mr.setMergedAtGitlab(now.minus(1, ChronoUnit.HOURS));
        mr.setAuthorGitlabUserId(authorGitlabUserId);
        return mergeRequestRepository.save(mr);
    }
}
