package io.simakov.analytics.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ScopeType;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.snapshot.SnapshotService;
import io.simakov.analytics.util.DateTimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for: historical snapshots showing lines_added=0 even after sync completes.
 *
 * <p>Root cause: the snapshot backfill triggered during user onboarding raced with
 * the ENRICH phase — MR net_additions were not yet populated, so all line metrics
 * came out 0. After ENRICH finished the snapshots were never re-computed.
 *
 * <p>Fix: SyncOrchestrator.orchestrateAsync now calls snapshotService.runDailyBackfillAsync
 * after ENRICH phase completes, which upserts all historical snapshots with correct values.
 */
class EnrichPhaseSnapshotBackfillTest extends BaseIT {

    /**
     * Run backfill only for today — keeps tests fast.
     */
    private static final int BACKFILL_ONLY_TODAY = 0;
    private static final int WINDOW_DAYS = 30;

    @Autowired
    private GitSourceRepository gitSourceRepository;
    @Autowired
    private TrackedProjectRepository trackedProjectRepository;
    @Autowired
    private TrackedUserRepository trackedUserRepository;
    @Autowired
    private TrackedUserAliasRepository aliasRepository;
    @Autowired
    private MergeRequestRepository mrRepository;
    @Autowired
    private MergeRequestCommitRepository commitRepository;
    @Autowired
    private MetricSnapshotRepository snapshotRepository;
    @Autowired
    private SnapshotService snapshotService;
    @Autowired
    private MrAuthorDiscoveryService authorDiscoveryService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long projectId;
    private Long aliceId;

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("gl").baseUrl("https://git.test").build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId()).gitlabProjectId(1L)
            .pathWithNamespace("team/repo").name("repo").tokenEncrypted("tok").enabled(true).build());
        projectId = project.getId();

        TrackedUser alice = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice").email("alice@example.com").enabled(true).build());
        aliceId = alice.getId();

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(aliceId).gitlabUserId(100L).email("alice@example.com").build());
    }

    /**
     * Core regression: stale snapshot with lines_added=0 is overwritten by backfill
     * once net_additions is set on the MR (i.e. after ENRICH phase finished).
     */
    @Test
    void backfillOverwritesStaleLinesAddedAfterEnrichPopulatesNetAdditions() {
        LocalDate today = DateTimeUtils.currentDateUtc();
        Instant mergedAt = today.minusDays(10).atStartOfDay().toInstant(ZoneOffset.UTC);

        mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(1L).gitlabMrIid(1L)
            .state(MrState.MERGED)
            .authorGitlabUserId(100L).authorUsername("alice")
            .createdAtGitlab(mergedAt.minusSeconds(3600))
            .mergedAtGitlab(mergedAt)
            .netAdditions(500)
            .netDeletions(120)
            .build());

        // Stale snapshot created during onboarding before ENRICH had finished
        saveStaleSnapshot(aliceId, today, WINDOW_DAYS);

        // Re-run backfill — same call SyncOrchestrator makes after ENRICH completes
        snapshotService.runDailyBackfill(testWorkspaceId, BACKFILL_ONLY_TODAY);

        Map<String, Object> metrics = loadMetrics(aliceId, today);
        assertThat(((Number) metrics.get("mr_merged_count")).intValue())
            .as("MR must be counted after backfill")
            .isEqualTo(1);
        assertThat(((Number) metrics.get("lines_added")).intValue())
            .as("lines_added must come from net_additions, not remain 0")
            .isEqualTo(500);
        assertThat(((Number) metrics.get("lines_deleted")).intValue())
            .as("lines_deleted must come from net_deletions")
            .isEqualTo(120);
    }

    /**
     * When net_additions is null (ENRICH not yet done), lines_added falls back
     * to commit-level stats instead of silently returning 0.
     */
    @Test
    void backfillFallsBackToCommitStatsWhenNetAdditionsIsNull() {
        LocalDate today = DateTimeUtils.currentDateUtc();
        Instant mergedAt = today.minusDays(5).atStartOfDay().toInstant(ZoneOffset.UTC);

        MergeRequest mr = mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(2L).gitlabMrIid(2L)
            .state(MrState.MERGED)
            .authorGitlabUserId(100L).authorUsername("alice")
            .createdAtGitlab(mergedAt.minusSeconds(3600))
            .mergedAtGitlab(mergedAt)
            .netAdditions(null) // ENRICH not finished yet — diff stats absent
            .netDeletions(null)
            .build());

        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("abc123")
            .authorEmail("alice@example.com")
            .authoredDate(mergedAt.minusSeconds(1800))
            .committedDate(mergedAt.minusSeconds(1800))
            .additions(200)
            .deletions(80)
            .totalChanges(280)
            .mergeCommit(false)
            .build());

        snapshotService.runDailyBackfill(testWorkspaceId, BACKFILL_ONLY_TODAY);

        Map<String, Object> metrics = loadMetrics(aliceId, today);
        assertThat(((Number) metrics.get("lines_added")).intValue())
            .as("lines_added falls back to commit stats when net_additions is null")
            .isEqualTo(200);
    }

    /**
     * Fresh snapshot created by backfill has correct lines_added from net_additions.
     */
    @Test
    void backfillCreatesFreshSnapshotWithCorrectLinesAdded() {
        LocalDate today = DateTimeUtils.currentDateUtc();
        Instant mergedAt = today.minusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC);

        mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(3L).gitlabMrIid(3L)
            .state(MrState.MERGED)
            .authorGitlabUserId(100L).authorUsername("alice")
            .createdAtGitlab(mergedAt.minusSeconds(3600))
            .mergedAtGitlab(mergedAt)
            .netAdditions(300)
            .netDeletions(50)
            .build());

        assertThat(snapshotRepository.findAll()).isEmpty();

        snapshotService.runDailyBackfill(testWorkspaceId, BACKFILL_ONLY_TODAY);

        Map<String, Object> metrics = loadMetrics(aliceId, today);
        assertThat(((Number) metrics.get("lines_added")).intValue()).isEqualTo(300);
        assertThat(((Number) metrics.get("lines_deleted")).intValue()).isEqualTo(50);
        assertThat(((Number) metrics.get("mr_merged_count")).intValue()).isEqualTo(1);
    }

    /**
     * Regression: commits_in_mr_count=0 for auto-discovered users.
     * <p>
     * Auto-discovered users have a gitlab_user_id alias but no email alias —
     * MR attribution works (by gitlab_user_id) but commit attribution fails (by email).
     * syncCommitEmails() reads commit author emails from the DB and links them to the
     * correct tracked user via the MR's author_gitlab_user_id.
     */
    @Test
    void syncCommitEmailsLinksCommitEmailToAutoDiscoveredUser() {
        // Bob: auto-discovered — alias has gitlabUserId but NO email (mirrors MrAuthorDiscoveryService)
        TrackedUser bob = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId).displayName("Bob").enabled(true).build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(bob.getId()).gitlabUserId(200L).username("bob").build()); // no email

        Instant mergedAt = DateTimeUtils.currentDateUtc().minusDays(5)
            .atStartOfDay().toInstant(ZoneOffset.UTC);
        MergeRequest mr = mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(10L).gitlabMrIid(10L)
            .state(MrState.MERGED)
            .authorGitlabUserId(200L).authorUsername("bob")
            .createdAtGitlab(mergedAt.minusSeconds(3600))
            .mergedAtGitlab(mergedAt)
            .netAdditions(100).netDeletions(10)
            .build());

        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("sha-bob-1")
            .authorEmail("bob@example.com")
            .authoredDate(mergedAt.minusSeconds(1800))
            .committedDate(mergedAt.minusSeconds(1800))
            .additions(100).deletions(10).totalChanges(110)
            .mergeCommit(false)
            .build());

        // Before: alias has no email
        assertThat(aliasRepository.findByTrackedUserId(bob.getId()))
            .extracting(TrackedUserAlias::getEmail)
            .containsOnlyNulls();

        int linked = authorDiscoveryService.syncCommitEmails(List.of(projectId));

        assertThat(linked).isEqualTo(1);
        Set<String> emails = aliasRepository.findByTrackedUserId(bob.getId()).stream()
            .map(TrackedUserAlias::getEmail)
            .filter(e -> e != null)
            .collect(Collectors.toSet());
        assertThat(emails).contains("bob@example.com");
    }

    /**
     * After syncCommitEmails + backfill, commits_in_mr_count is non-zero for auto-discovered users.
     */
    @Test
    void commitsInMrCountIsNonZeroAfterSyncCommitEmailsAndBackfill() {
        // Bob: auto-discovered — no email alias
        TrackedUser bob = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId).displayName("Bob").enabled(true).build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(bob.getId()).gitlabUserId(200L).username("bob").build());

        LocalDate today = DateTimeUtils.currentDateUtc();
        Instant mergedAt = today.minusDays(5).atStartOfDay().toInstant(ZoneOffset.UTC);

        MergeRequest mr = mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(11L).gitlabMrIid(11L)
            .state(MrState.MERGED)
            .authorGitlabUserId(200L).authorUsername("bob")
            .createdAtGitlab(mergedAt.minusSeconds(3600))
            .mergedAtGitlab(mergedAt)
            .netAdditions(50).netDeletions(5)
            .build());

        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("sha-bob-2")
            .authorEmail("bob@example.com")
            .authoredDate(mergedAt.minusSeconds(1800))
            .committedDate(mergedAt.minusSeconds(1800))
            .additions(50).deletions(5).totalChanges(55)
            .mergeCommit(false)
            .build());

        // Simulate what SyncOrchestrator does after ENRICH
        authorDiscoveryService.syncCommitEmails(List.of(projectId));
        snapshotService.runDailyBackfill(testWorkspaceId, BACKFILL_ONLY_TODAY);

        Map<String, Object> metrics = loadMetrics(bob.getId(), today);
        assertThat(((Number) metrics.get("commits_in_mr_count")).intValue())
            .as("commits_in_mr_count must be > 0 after email aliases are linked")
            .isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void saveStaleSnapshot(Long userId,
                                   LocalDate date,
                                   int windowDays) {
        Instant dateTo = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dateFrom = date.minusDays(windowDays).atStartOfDay().toInstant(ZoneOffset.UTC);
        snapshotRepository.save(MetricSnapshot.builder()
            .workspaceId(testWorkspaceId)
            .trackedUserId(userId)
            .snapshotDate(date)
            .dateFrom(dateFrom)
            .dateTo(dateTo)
            .windowDays(windowDays)
            .periodType(PeriodType.CUSTOM)
            .scopeType(ScopeType.USER)
            .metricsJson("{\"lines_added\":0,\"lines_deleted\":0,\"mr_merged_count\":0}")
            .build());
    }

    private Map<String, Object> loadMetrics(Long userId,
                                            LocalDate date) {
        MetricSnapshot snapshot = snapshotRepository
            .findByWorkspaceIdAndSnapshotDateAndTrackedUserIdIn(testWorkspaceId, date, List.of(userId))
            .stream().findFirst()
            .orElseThrow(() -> new AssertionError("Snapshot not found for user=" + userId + " date=" + date));
        try {
            return objectMapper.readValue(snapshot.getMetricsJson(), new TypeReference<>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("Failed to parse metricsJson", e);
        }
    }
}
