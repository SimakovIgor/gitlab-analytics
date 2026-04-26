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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlowControllerTest extends BaseIT {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

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
    void flowPageReturns200ForAuthenticatedUser() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Flow");
        assertThat(html).contains("где MR залипают");
    }

    @Test
    void flowPageRedirectsToLoginWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/flow"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void flowPageShowsEmptyStateWhenNoMergedMrs() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет данных о смёрженных MR");
    }

    // ── Period filter ───────────────────────────────────────────────────

    @Test
    void flowPageRespectsPeriodParameter() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow?period=LAST_7_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("period-seg-btn-active");
    }

    @Test
    void flowPageHandlesInvalidPeriodGracefully() throws Exception {
        mockMvc.perform(get("/flow?period=INVALID")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk());
    }

    // ── Flow stages ─────────────────────────────────────────────────────

    @Test
    void flowPageShowsStagesWhenMergedMrsExist() throws Exception {
        saveMergedMr(1L, 48);

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Среднее время MR по стадиям");
        assertThat(html).contains("ИТОГО");
        assertThat(html).contains("Review Wait");
    }

    @Test
    void flowPageCalculatesStagesWithReviewData() throws Exception {
        MergeRequest mr = saveMergedMr(1L, 24);

        // Add external review note 6 hours after creation
        noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mr.getId())
            .gitlabNoteId(100L)
            .authorGitlabUserId(999L)
            .authorUsername("reviewer")
            .authorName("Reviewer")
            .body("LGTM")
            .system(false)
            .internal(false)
            .createdAtGitlab(now.minus(18, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // Should have non-zero stages rendered (Review Wait should have data from the 6-hour gap)
        assertThat(html).contains("Review Wait");
        assertThat(html).contains("Review");
        assertThat(html).contains("Merge Wait");
    }

    // ── Stuck MRs ───────────────────────────────────────────────────────

    @Test
    void flowPageShowsStuckMrs() throws Exception {
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(100L)
            .gitlabMrIid(100L)
            .state(MrState.OPENED)
            .title("Very old open MR")
            .authorGitlabUserId(42L)
            .authorName("Dev One")
            .createdAtGitlab(now.minus(5, ChronoUnit.DAYS))
            .webUrl("https://git.example.com/mr/100")
            .additions(50)
            .deletions(10)
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_30_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Застрявшие MR");
        assertThat(html).contains("Very old open MR");
        assertThat(html).contains("Dev One");
    }

    @Test
    void flowPageFiltersStuckMrsByHoursThreshold() throws Exception {
        // MR open for 2 days — should appear at 24h threshold but not at 7d threshold
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(101L)
            .gitlabMrIid(101L)
            .state(MrState.OPENED)
            .title("2-day old MR")
            .authorGitlabUserId(42L)
            .authorName("Dev")
            .createdAtGitlab(now.minus(2, ChronoUnit.DAYS))
            .additions(10)
            .deletions(5)
            .build());

        MvcResult at24h = mockMvc.perform(get("/flow?stuckHours=24")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(at24h.getResponse().getContentAsString()).contains("2-day old MR");

        MvcResult at7d = mockMvc.perform(get("/flow?stuckHours=168")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(at7d.getResponse().getContentAsString()).doesNotContain("2-day old MR");
    }

    @Test
    void flowPageShowsEmptyStuckState() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет застрявших MR");
    }

    @Test
    void flowPageAssignsCorrectSeverityToStuckMrs() throws Exception {
        // MR open > 30 days → severity = bad
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(102L)
            .gitlabMrIid(102L)
            .state(MrState.OPENED)
            .title("Ancient MR")
            .authorGitlabUserId(42L)
            .authorName("Dev")
            .createdAtGitlab(now.minus(45, ChronoUnit.DAYS))
            .additions(5)
            .deletions(2)
            .build());

        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Ancient MR");
        assertThat(html).contains("var(--bad)");
    }

    // ── Review balance ──────────────────────────────────────────────────

    @Test
    void flowPageShowsReviewBalance() throws Exception {
        createTrackedUser("reviewer@example.com", "Alice Reviewer", 100L);
        MergeRequest mr = saveMergedMr(200L, 12);

        // Alice reviewed this MR (note from someone other than author)
        noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mr.getId())
            .gitlabNoteId(300L)
            .authorGitlabUserId(100L)
            .authorUsername("alice")
            .authorName("Alice Reviewer")
            .body("Good work")
            .system(false)
            .internal(false)
            .createdAtGitlab(now.minus(6, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Review-нагрузка");
        assertThat(html).contains("Alice Reviewer");
    }

    @Test
    void flowPageShowsEmptyReviewBalanceWhenNoReviews() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет данных о ревью за период");
    }

    @Test
    void flowPageExcludesAuthorFromReviewBalance() throws Exception {
        createTrackedUser("author@example.com", "Bob Author", 42L);
        MergeRequest mr = saveMergedMr(201L, 12);

        // Author commenting on own MR — should NOT count as review
        noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mr.getId())
            .gitlabNoteId(301L)
            .authorGitlabUserId(42L)
            .authorUsername("bob")
            .authorName("Bob Author")
            .body("Self comment")
            .system(false)
            .internal(false)
            .createdAtGitlab(now.minus(6, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // Self-review should not appear in balance
        assertThat(html).contains("Нет данных о ревью за период");
    }

    @Test
    void flowPageCountsApprovalsInReviewBalance() throws Exception {
        createTrackedUser("approver@example.com", "Carol Approver", 200L);
        MergeRequest mr = saveMergedMr(202L, 12);

        approvalRepository.save(MergeRequestApproval.builder()
            .mergeRequestId(mr.getId())
            .approvedByGitlabUserId(200L)
            .approvedByUsername("carol")
            .approvedByName("Carol Approver")
            .approvedAtGitlab(now.minus(4, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Carol Approver");
    }

    // ── Review matrix ───────────────────────────────────────────────────

    @Test
    void flowPageShowsReviewMatrix() throws Exception {
        createTrackedUser("author@example.com", "Dave Author", 42L);
        createTrackedUser("reviewer@example.com", "Eve Reviewer", 100L);

        MergeRequest mr = saveMergedMr(300L, 12);

        noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mr.getId())
            .gitlabNoteId(400L)
            .authorGitlabUserId(100L)
            .authorUsername("eve")
            .authorName("Eve Reviewer")
            .body("Needs fix")
            .system(false)
            .internal(false)
            .createdAtGitlab(now.minus(6, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Матрица ревью");
        assertThat(html).contains("кто кого ревьюит");
        // Matrix data should contain both names
        assertThat(html).contains("Dave");
        assertThat(html).contains("Eve");
    }

    @Test
    void flowPageShowsEmptyMatrixWhenNoReviews() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Нет данных о ревью для построения матрицы");
    }

    @Test
    void flowPageExcludesSystemNotesFromMatrix() throws Exception {
        createTrackedUser("sys-author@example.com", "Frank", 50L);
        createTrackedUser("sys-reviewer@example.com", "Grace", 60L);

        MergeRequest mr = saveMergedMr(301L, 12);

        // System note — should NOT count
        noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mr.getId())
            .gitlabNoteId(401L)
            .authorGitlabUserId(60L)
            .authorUsername("grace")
            .authorName("Grace")
            .body("added 2 commits")
            .system(true)
            .internal(false)
            .createdAtGitlab(now.minus(6, ChronoUnit.HOURS))
            .build());

        MvcResult result = mockMvc.perform(get("/flow?period=LAST_90_DAYS")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        // System notes excluded → empty matrix
        assertThat(html).contains("Нет данных о ревью для построения матрицы");
    }

    // ── Project filter ──────────────────────────────────────────────────

    @Test
    void flowPageFiltersDataByProjectIds() throws Exception {
        // MR in our project
        mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(500L)
            .gitlabMrIid(500L)
            .state(MrState.OPENED)
            .title("Our project MR")
            .authorGitlabUserId(42L)
            .authorName("Dev")
            .createdAtGitlab(now.minus(3, ChronoUnit.DAYS))
            .additions(10)
            .deletions(5)
            .build());

        // With non-existing project filter → should not see stuck MR
        MvcResult result = mockMvc.perform(get("/flow?projectIds=99999")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).doesNotContain("Our project MR");
    }

    @Test
    void flowPageShowsProjectCheckboxes() throws Exception {
        MvcResult result = mockMvc.perform(get("/flow")
                .session(webSession)
                .with(user("owner@test.com").roles("USER")))
            .andExpect(status().isOk())
            .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("repo");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private MergeRequest saveMergedMr(Long gitlabMrId,
                                      int leadHours) {
        return mergeRequestRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(gitlabMrId)
            .gitlabMrIid(gitlabMrId)
            .state(MrState.MERGED)
            .title("MR #" + gitlabMrId)
            .createdAtGitlab(now.minus(leadHours, ChronoUnit.HOURS))
            .mergedAtGitlab(now.minus(1, ChronoUnit.HOURS))
            .authorGitlabUserId(42L)
            .authorName("MR Author")
            .additions(20)
            .deletions(5)
            .build());
    }

    private TrackedUser createTrackedUser(String email,
                                          String name,
                                          Long gitlabUserId) {
        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .email(email)
            .displayName(name)
            .build());

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(user.getId())
            .gitlabUserId(gitlabUserId)
            .email(email)
            .build());

        return user;
    }
}
