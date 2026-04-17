package io.simakov.analytics.metrics;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.ContributionReportRequest;
import io.simakov.analytics.api.dto.response.ContributionReportResponse;
import io.simakov.analytics.api.dto.response.ContributionReportResponse.ContributionResult;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MergeRequestDiscussion;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.GroupBy;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestDiscussionRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({
    "PMD.JUnitTestContainsTooManyAsserts",
    "checkstyle:ClassDataAbstractionCoupling",
    "checkstyle:MethodCount"
})
class MetricCalculationTest extends BaseIT {

    private static final long ALICE_GITLAB_ID = 100L;
    private static final long BOB_GITLAB_ID = 200L;
    private static final String ALICE_EMAIL = "alice@example.com";
    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-01-31T23:59:59Z");
    /**
     * Base instant inside the period used as an anchor for relative timestamps.
     */
    private static final Instant T = Instant.parse("2026-01-15T10:00:00Z");
    private static final AtomicLong SEQ = new AtomicLong(0);

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
    private MergeRequestNoteRepository noteRepository;
    @Autowired
    private MergeRequestApprovalRepository approvalRepository;
    @Autowired
    private MergeRequestDiscussionRepository discussionRepository;

    private Long projectId;
    private Long aliceId;
    private Long gitSourceId;

    @BeforeEach
    void setUpFixtures() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .name("test-gitlab")
            .baseUrl("https://git.test")
            .tokenEncrypted("tok")
            .build());
        gitSourceId = source.getId();
        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(gitSourceId)
            .gitlabProjectId(42L)
            .pathWithNamespace("team/repo")
            .name("repo")
            .enabled(true)
            .build());
        projectId = project.getId();
        TrackedUser alice = trackedUserRepository.save(TrackedUser.builder()
            .displayName("Alice")
            .email(ALICE_EMAIL)
            .enabled(true)
            .build());
        aliceId = alice.getId();
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(aliceId)
            .gitlabUserId(ALICE_GITLAB_ID)
            .username("alice")
            .email(ALICE_EMAIL)
            .build());
    }

    // =========================================================================
    // DELIVERY
    // =========================================================================

    @Test
    void mrMergedCountOnlyIncludesMrsWithMergedAtInPeriod() {
        saveMergedMrByAlice(T);
        saveMergedMrByAlice(T.plusSeconds(1800));
        // Merged before period start — must not appear
        long id = seqNext();
        mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(id).gitlabMrIid(id)
            .state(MrState.MERGED)
            .authorGitlabUserId(ALICE_GITLAB_ID).authorUsername("alice")
            .createdAtGitlab(Instant.parse("2025-12-01T00:00:00Z"))
            .mergedAtGitlab(Instant.parse("2025-12-20T00:00:00Z"))
            .mergedByGitlabUserId(ALICE_GITLAB_ID)
            .build());

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "mr_merged_count")).isEqualTo(2);
        assertThat(intMetric(result, "mr_opened_count")).isEqualTo(2);
    }

    @Test
    void commitsInMrCountMatchedByEmailNotGitlabId() {
        MergeRequest mr = saveMergedMrByAlice(T);
        saveCommitByAlice(mr.getId(), 10, 5, T.minusSeconds(1200));
        saveCommitByAlice(mr.getId(), 20, 0, T.minusSeconds(600));
        saveCommitByBob(mr.getId(), 100, 50, T.minusSeconds(300)); // Bob's — must not count

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "commits_in_mr_count")).isEqualTo(2);
    }

    @Test
    void commitsInMrCountZeroWhenEmailMismatch() {
        MergeRequest mr = saveMergedMrByAlice(T);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("sha-unknown-" + seqNext())
            .authorEmail("nobody@example.com")
            .authoredDate(T.minusSeconds(600))
            .additions(50).deletions(25).totalChanges(75)
            .build());

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "commits_in_mr_count")).isZero();
        assertThat(intMetric(result, "lines_added")).isZero();
    }

    @Test
    void activeDaysCountAggregatesAcrossCommitsNotesAndApprovals() {
        MergeRequest aliceMr = saveMergedMrByAlice(T);
        MergeRequest bobMr = saveMergedMrByBob(T);
        saveCommitByAlice(aliceMr.getId(), 1, 0, Instant.parse("2026-01-15T10:00:00Z")); // day 1
        saveNoteByAlice(bobMr.getId(), false, Instant.parse("2026-01-16T10:00:00Z"));    // day 2
        saveApprovalByAlice(bobMr.getId(), Instant.parse("2026-01-17T10:00:00Z"));       // day 3

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "active_days_count")).isEqualTo(3);
    }

    @Test
    void activeDaysCountSameCalendarDayCountsOnce() {
        MergeRequest aliceMr = saveMergedMrByAlice(T);
        MergeRequest bobMr = saveMergedMrByBob(T);
        Instant sameDay = Instant.parse("2026-01-15T10:00:00Z");
        saveCommitByAlice(aliceMr.getId(), 1, 0, sameDay);
        saveNoteByAlice(bobMr.getId(), false, sameDay.plusSeconds(1800));
        saveApprovalByAlice(bobMr.getId(), sameDay.plusSeconds(3600));

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "active_days_count")).isEqualTo(1);
    }

    @Test
    void repositoriesTouchedCountAcrossMultipleProjects() {
        TrackedProject project2 = trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(gitSourceId)
            .gitlabProjectId(99L).pathWithNamespace("team/repo2").name("repo2").enabled(true)
            .build());
        saveMergedMrByAlice(T);
        long id = seqNext();
        mrRepository.save(MergeRequest.builder()
            .trackedProjectId(project2.getId())
            .gitlabMrId(id).gitlabMrIid(id)
            .state(MrState.MERGED)
            .authorGitlabUserId(ALICE_GITLAB_ID).authorUsername("alice")
            .createdAtGitlab(T.minusSeconds(3600)).mergedAtGitlab(T)
            .mergedByGitlabUserId(ALICE_GITLAB_ID)
            .build());

        ContributionResult result = aliceReport(List.of(projectId, project2.getId()));
        assertThat(intMetric(result, "repositories_touched_count")).isEqualTo(2);
    }

    // =========================================================================
    // CHANGE VOLUME
    // =========================================================================

    @Test
    void linesAddedAndDeletedFromUserCommitsOnly() {
        MergeRequest mr = saveMergedMrByAlice(T);
        saveCommitByAlice(mr.getId(), 100, 50, T.minusSeconds(600));
        saveCommitByBob(mr.getId(), 200, 100, T.minusSeconds(300)); // must not count

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "lines_added")).isEqualTo(100);
        assertThat(intMetric(result, "lines_deleted")).isEqualTo(50);
        assertThat(intMetric(result, "lines_changed")).isEqualTo(150);
    }

    @Test
    void mrSizeLinesIncludesAllCommitsNotJustUser() {
        // Alice commit: +10 -5 = 15; Bob commit: +20 -10 = 30; MR total = 45
        MergeRequest mr = saveMergedMrByAlice(T);
        saveCommitByAlice(mr.getId(), 10, 5, T.minusSeconds(600));
        saveCommitByBob(mr.getId(), 20, 10, T.minusSeconds(300));

        ContributionResult result = aliceReport();
        assertThat(dblMetric(result, "avg_mr_size_lines")).isEqualTo(45.0);
        assertThat(dblMetric(result, "median_mr_size_lines")).isEqualTo(45.0);
    }

    @Test
    void medianMrSizeLinesOddCountReturnsMiddleValue() {
        // Sizes: 10, 30, 50 → median = 30
        MergeRequest mr1 = saveMergedMrByAlice(T);
        MergeRequest mr2 = saveMergedMrByAlice(T.plusSeconds(1));
        MergeRequest mr3 = saveMergedMrByAlice(T.plusSeconds(2));
        saveCommitByAlice(mr1.getId(), 10, 0, T.minusSeconds(600));
        saveCommitByAlice(mr2.getId(), 30, 0, T.minusSeconds(600));
        saveCommitByAlice(mr3.getId(), 50, 0, T.minusSeconds(600));

        ContributionResult result = aliceReport();
        assertThat(dblMetric(result, "median_mr_size_lines")).isEqualTo(30.0);
        assertThat(dblMetric(result, "avg_mr_size_lines")).isEqualTo(30.0);
    }

    @Test
    void medianMrSizeLinesEvenCountIsAverageOfMiddleTwo() {
        // Sizes: 10, 30 → median = 20.0
        MergeRequest mr1 = saveMergedMrByAlice(T);
        MergeRequest mr2 = saveMergedMrByAlice(T.plusSeconds(1));
        saveCommitByAlice(mr1.getId(), 10, 0, T.minusSeconds(600));
        saveCommitByAlice(mr2.getId(), 30, 0, T.minusSeconds(600));

        ContributionResult result = aliceReport();
        assertThat(dblMetric(result, "median_mr_size_lines")).isEqualTo(20.0);
    }

    @Test
    void mrSizeLinesExcludesMrsWithNoCommits() {
        // mr1: size 40; mr2: no commits → excluded → avg=40, median=40
        MergeRequest mr1 = saveMergedMrByAlice(T);
        saveMergedMrByAlice(T.plusSeconds(1));
        saveCommitByAlice(mr1.getId(), 30, 10, T.minusSeconds(600));

        ContributionResult result = aliceReport();
        assertThat(dblMetric(result, "avg_mr_size_lines")).isEqualTo(40.0);
        assertThat(dblMetric(result, "median_mr_size_lines")).isEqualTo(40.0);
    }

    // =========================================================================
    // REVIEW
    // =========================================================================

    @Test
    void reviewCommentsCountedInForeignMrsOnly() {
        MergeRequest aliceMr = saveMergedMrByAlice(T);
        MergeRequest bobMr = saveMergedMrByBob(T);
        saveNoteByAlice(aliceMr.getId(), false, T.minusSeconds(600)); // own MR — must NOT count
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(400));
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(200));

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "review_comments_written_count")).isEqualTo(2);
    }

    @Test
    void reviewCommentsSystemNotesNotCounted() {
        MergeRequest bobMr = saveMergedMrByBob(T);
        saveNoteByAlice(bobMr.getId(), true, T.minusSeconds(600));  // system — ignored
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(300)); // regular — counted

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "review_comments_written_count")).isEqualTo(1);
    }

    @Test
    void approvalsGivenCountOnlyForeignMrs() {
        MergeRequest aliceMr = saveMergedMrByAlice(T);
        MergeRequest bobMr = saveMergedMrByBob(T);
        saveApprovalByAlice(aliceMr.getId(), T.minusSeconds(300)); // own MR — must NOT count
        saveApprovalByAlice(bobMr.getId(), T.minusSeconds(200));   // foreign — counted

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "approvals_given_count")).isEqualTo(1);
    }

    @Test
    void mrsReviewedCountDeduplicatesNotesInSameMr() {
        // 3 notes in 1 foreign MR → mrs_reviewed = 1
        MergeRequest bobMr = saveMergedMrByBob(T);
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(600));
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(400));
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(200));

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "mrs_reviewed_count")).isEqualTo(1);
    }

    @Test
    void mrsReviewedCountUnifiesNotesAndApprovals() {
        MergeRequest bobMr1 = saveMergedMrByBob(T);
        MergeRequest bobMr2 = saveMergedMrByBob(T.plusSeconds(1));
        saveNoteByAlice(bobMr1.getId(), false, T.minusSeconds(600)); // note in mr1
        saveApprovalByAlice(bobMr2.getId(), T.minusSeconds(300));    // approval in mr2 → 2 MRs

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "mrs_reviewed_count")).isEqualTo(2);
    }

    @Test
    void mrsReviewedCountNoteAndApprovalInSameMrCountsOnce() {
        MergeRequest bobMr = saveMergedMrByBob(T);
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(600));
        saveApprovalByAlice(bobMr.getId(), T.minusSeconds(300));

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "mrs_reviewed_count")).isEqualTo(1);
    }

    @Test
    void reviewThreadsStartedWhenAliceStartsDiscussion() {
        MergeRequest bobMr = saveMergedMrByBob(T);
        MergeRequestDiscussion disc = saveDiscussion(bobMr.getId());
        saveThreadedNote(bobMr.getId(), disc.getId(), ALICE_GITLAB_ID, T.minusSeconds(600)); // Alice first
        saveThreadedNote(bobMr.getId(), disc.getId(), BOB_GITLAB_ID, T.minusSeconds(300));   // Bob replies

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "review_threads_started_count")).isEqualTo(1);
    }

    @Test
    void reviewThreadsStartedZeroWhenAliceReplies() {
        MergeRequest bobMr = saveMergedMrByBob(T);
        MergeRequestDiscussion disc = saveDiscussion(bobMr.getId());
        saveThreadedNote(bobMr.getId(), disc.getId(), BOB_GITLAB_ID, T.minusSeconds(600));   // Bob first
        saveThreadedNote(bobMr.getId(), disc.getId(), ALICE_GITLAB_ID, T.minusSeconds(300)); // Alice replies

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "review_threads_started_count")).isZero();
    }

    @Test
    void reviewThreadsStartedAcrossMultipleDiscussions() {
        MergeRequest bobMr = saveMergedMrByBob(T);
        MergeRequestDiscussion disc1 = saveDiscussion(bobMr.getId());
        MergeRequestDiscussion disc2 = saveDiscussion(bobMr.getId());
        saveThreadedNote(bobMr.getId(), disc1.getId(), ALICE_GITLAB_ID, T.minusSeconds(600)); // starts disc1
        saveThreadedNote(bobMr.getId(), disc2.getId(), ALICE_GITLAB_ID, T.minusSeconds(400)); // starts disc2
        saveThreadedNote(bobMr.getId(), disc2.getId(), BOB_GITLAB_ID, T.minusSeconds(200));   // reply in disc2

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "review_threads_started_count")).isEqualTo(2);
    }

    // =========================================================================
    // FLOW: TIME TO FIRST REVIEW
    // =========================================================================

    @Test
    void timeToFirstReviewExternalNoteIsUsed() {
        // MR created at T, Bob notes 30 min later → median = 30 min
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveNoteByBob(mr.getId(), false, T.plusSeconds(1800));

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_first_review_minutes")).isEqualTo(30.0);
    }

    @Test
    void timeToFirstReviewUsesEarliestOfNoteAndApproval() {
        // Approval at T+10min, note at T+30min → first review at T+10 (approval wins)
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveApprovalByBob(mr.getId(), T.plusSeconds(600));    // T+10min
        saveNoteByBob(mr.getId(), false, T.plusSeconds(1800)); // T+30min

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_first_review_minutes")).isEqualTo(10.0);
    }

    @Test
    void timeToFirstReviewSystemNoteIsIgnored() {
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveNoteByBob(mr.getId(), true, T.plusSeconds(300)); // system note — must be ignored

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_first_review_minutes")).isNull();
    }

    @Test
    void timeToFirstReviewOwnNoteIsIgnored() {
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveNoteByAlice(mr.getId(), false, T.plusSeconds(600)); // own note — must be ignored

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_first_review_minutes")).isNull();
    }

    @Test
    void timeToFirstReviewNullWhenNoExternalActivity() {
        saveMergedMrByAlice(T);

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_first_review_minutes")).isNull();
        assertThat(nullableMetric(result, "avg_time_to_first_review_minutes")).isNull();
    }

    @Test
    void timeToFirstReviewMedianOfMultipleMrs() {
        // All MRs created at T; review at T+10min, T+30min, T+50min → median=30, avg=30
        MergeRequest mr1 = saveMergedMrByAlice(T, T.plusSeconds(7200));
        MergeRequest mr2 = saveMergedMrByAlice(T, T.plusSeconds(7201));
        MergeRequest mr3 = saveMergedMrByAlice(T, T.plusSeconds(7202));
        saveNoteByBob(mr1.getId(), false, T.plusSeconds(600));   // T+10min
        saveNoteByBob(mr2.getId(), false, T.plusSeconds(1800));  // T+30min
        saveNoteByBob(mr3.getId(), false, T.plusSeconds(3000));  // T+50min

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_first_review_minutes")).isEqualTo(30.0);
        assertThat(nullableMetric(result, "avg_time_to_first_review_minutes")).isEqualTo(30.0);
    }

    // =========================================================================
    // FLOW: TIME TO MERGE
    // =========================================================================

    @Test
    void timeToMergeFromCreatedAtToMergedAt() {
        // Created at T, merged at T+120min
        saveMergedMrByAlice(T, T.plusSeconds(7200));

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_merge_minutes")).isEqualTo(120.0);
        assertThat(nullableMetric(result, "avg_time_to_merge_minutes")).isEqualTo(120.0);
    }

    @Test
    void timeToMergeMedianAcrossMultipleMrs() {
        // MR1=60min, MR2=120min, MR3=180min → median=120
        saveMergedMrByAlice(T, T.plusSeconds(3600));
        saveMergedMrByAlice(T.plusSeconds(1), T.plusSeconds(7201));
        saveMergedMrByAlice(T.plusSeconds(2), T.plusSeconds(10_802));

        ContributionResult result = aliceReport();
        assertThat(nullableMetric(result, "median_time_to_merge_minutes")).isEqualTo(120.0);
    }

    // =========================================================================
    // FLOW: REWORK
    // =========================================================================

    @Test
    void reworkDetectedWhenUserCommitsAfterFirstExternalReview() {
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveNoteByBob(mr.getId(), false, T.plusSeconds(1800));     // review at T+30min
        saveCommitByAlice(mr.getId(), 10, 5, T.plusSeconds(3600)); // Alice commits at T+60min → rework

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "rework_mr_count")).isEqualTo(1);
        assertThat(dblMetric(result, "rework_ratio")).isEqualTo(1.0);
    }

    @Test
    void reworkNotDetectedWhenAllCommitsBeforeReview() {
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveCommitByAlice(mr.getId(), 10, 5, T.plusSeconds(600)); // commit at T+10min
        saveNoteByBob(mr.getId(), false, T.plusSeconds(1800));    // review at T+30min — commit was before

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "rework_mr_count")).isZero();
        assertThat(dblMetric(result, "rework_ratio")).isZero();
    }

    @Test
    void reworkForeignCommitAfterReviewDoesNotCountForUser() {
        // Bob's commit after review is NOT Alice's rework
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveNoteByBob(mr.getId(), false, T.plusSeconds(1800));
        saveCommitByBob(mr.getId(), 10, 5, T.plusSeconds(3600));

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "rework_mr_count")).isZero();
    }

    @Test
    void reworkNotCountedWithoutExternalReview() {
        // No external review → rework window never opens
        MergeRequest mr = saveMergedMrByAlice(T, T.plusSeconds(7200));
        saveCommitByAlice(mr.getId(), 10, 5, T.plusSeconds(3600));

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "rework_mr_count")).isZero();
    }

    // =========================================================================
    // FLOW: SELF-MERGE
    // =========================================================================

    @Test
    void selfMergeRatioOneWhenAuthorMergesOwnMr() {
        saveMergedMrByAlice(T); // mergedByGitlabUserId = ALICE_GITLAB_ID by default

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "self_merge_count")).isEqualTo(1);
        assertThat(dblMetric(result, "self_merge_ratio")).isEqualTo(1.0);
    }

    @Test
    void selfMergeRatioZeroWhenMergedByOther() {
        long id = seqNext();
        mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId).gitlabMrId(id).gitlabMrIid(id)
            .state(MrState.MERGED)
            .authorGitlabUserId(ALICE_GITLAB_ID).authorUsername("alice")
            .createdAtGitlab(T.minusSeconds(3600)).mergedAtGitlab(T)
            .mergedByGitlabUserId(BOB_GITLAB_ID) // Bob merged it
            .build());

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "self_merge_count")).isZero();
        assertThat(dblMetric(result, "self_merge_ratio")).isZero();
    }

    @Test
    void selfMergeRatioZeroWhenMergedByIsNull() {
        long id = seqNext();
        mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId).gitlabMrId(id).gitlabMrIid(id)
            .state(MrState.MERGED)
            .authorGitlabUserId(ALICE_GITLAB_ID).authorUsername("alice")
            .createdAtGitlab(T.minusSeconds(3600)).mergedAtGitlab(T)
            .mergedByGitlabUserId(null)
            .build());

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "self_merge_count")).isZero();
    }

    // =========================================================================
    // NORMALIZED
    // =========================================================================

    @Test
    void mrMergedPerActiveDayNormalized() {
        // 2 MRs merged, 4 active days → 2/4 = 0.5
        MergeRequest mr1 = saveMergedMrByAlice(T);
        MergeRequest mr2 = saveMergedMrByAlice(T.plusSeconds(86_400));
        saveCommitByAlice(mr1.getId(), 1, 0, Instant.parse("2026-01-15T10:00:00Z"));
        saveCommitByAlice(mr1.getId(), 1, 0, Instant.parse("2026-01-16T10:00:00Z"));
        saveCommitByAlice(mr2.getId(), 1, 0, Instant.parse("2026-01-17T10:00:00Z"));
        saveCommitByAlice(mr2.getId(), 1, 0, Instant.parse("2026-01-18T10:00:00Z"));

        ContributionResult result = aliceReport();
        assertThat(result.normalized().get("mr_merged_per_active_day")).isEqualTo(0.5);
    }

    @Test
    void commentsPerReviewedMrNormalized() {
        // 3 comments in 2 foreign MRs → 3/2 = 1.5
        MergeRequest bob1 = saveMergedMrByBob(T);
        MergeRequest bob2 = saveMergedMrByBob(T.plusSeconds(1));
        saveNoteByAlice(bob1.getId(), false, T.minusSeconds(600));
        saveNoteByAlice(bob1.getId(), false, T.minusSeconds(400));
        saveNoteByAlice(bob2.getId(), false, T.minusSeconds(200));

        ContributionResult result = aliceReport();
        assertThat(result.normalized().get("comments_per_reviewed_mr")).isEqualTo(1.5);
    }

    // =========================================================================
    // EMAIL MATCHING
    // =========================================================================

    @Test
    void commitAttributedByAliasEmailDifferentFromPrimary() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(aliceId)
            .gitlabUserId(ALICE_GITLAB_ID)
            .username("alice-work")
            .email("alice@corp.example.com")
            .build());
        MergeRequest mr = saveMergedMrByAlice(T);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("sha-alias-" + seqNext())
            .authorEmail("alice@corp.example.com")
            .authoredDate(T.minusSeconds(600))
            .additions(77).deletions(33).totalChanges(110)
            .build());

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "commits_in_mr_count")).isEqualTo(1);
        assertThat(intMetric(result, "lines_added")).isEqualTo(77);
    }

    @Test
    void commitAttributedByPrimaryEmailCaseInsensitive() {
        MergeRequest mr = saveMergedMrByAlice(T);
        commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mr.getId())
            .gitlabCommitSha("sha-upper-" + seqNext())
            .authorEmail("ALICE@EXAMPLE.COM")
            .authoredDate(T.minusSeconds(600))
            .additions(55).deletions(0).totalChanges(55)
            .build());

        ContributionResult result = aliceReport();
        assertThat(intMetric(result, "commits_in_mr_count")).isEqualTo(1);
        assertThat(intMetric(result, "lines_added")).isEqualTo(55);
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    void userWithNoAliasesHasAllZeroMetrics() {
        TrackedUser noAlias = trackedUserRepository.save(TrackedUser.builder()
            .displayName("NoAlias").email("noalias@example.com").enabled(true)
            .build());
        saveMergedMrByAlice(T);

        ContributionResult result = requestReport(List.of(projectId), List.of(noAlias.getId()));
        assertThat(intMetric(result, "mr_merged_count")).isZero();
        assertThat(intMetric(result, "commits_in_mr_count")).isZero();
        assertThat(intMetric(result, "review_comments_written_count")).isZero();
    }

    @Test
    void twoUsersMetricsAreCalculatedIndependently() {
        TrackedUser bob = trackedUserRepository.save(TrackedUser.builder()
            .displayName("Bob").email("bob@example.com").enabled(true)
            .build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(bob.getId()).gitlabUserId(BOB_GITLAB_ID)
            .username("bob").email("bob@example.com")
            .build());
        MergeRequest aliceMr = saveMergedMrByAlice(T);
        MergeRequest bobMr = saveMergedMrByBob(T.plusSeconds(1));
        saveCommitByAlice(aliceMr.getId(), 10, 5, T.minusSeconds(600));
        saveCommitByBob(bobMr.getId(), 20, 8, T.minusSeconds(600));
        saveNoteByAlice(bobMr.getId(), false, T.minusSeconds(300));
        saveNoteByBob(aliceMr.getId(), false, T.minusSeconds(300));

        ContributionReportRequest req = new ContributionReportRequest(
            List.of(projectId), List.of(aliceId, bob.getId()),
            PeriodType.CUSTOM, PERIOD_START, PERIOD_END,
            GroupBy.USER, null
        );
        ResponseEntity<ContributionReportResponse> resp = restTemplate.exchange(
            "/api/v1/reports/contributions",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            ContributionReportResponse.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().results()).hasSize(2);

        ContributionResult aliceResult = resp.getBody().results().stream()
            .filter(r -> r.userId().equals(aliceId)).findFirst().orElseThrow();
        ContributionResult bobResult = resp.getBody().results().stream()
            .filter(r -> r.userId().equals(bob.getId())).findFirst().orElseThrow();

        assertThat(intMetric(aliceResult, "mr_merged_count")).isEqualTo(1);
        assertThat(intMetric(aliceResult, "lines_added")).isEqualTo(10);
        assertThat(intMetric(aliceResult, "review_comments_written_count")).isEqualTo(1);
        assertThat(intMetric(bobResult, "mr_merged_count")).isEqualTo(1);
        assertThat(intMetric(bobResult, "lines_added")).isEqualTo(20);
        assertThat(intMetric(bobResult, "review_comments_written_count")).isEqualTo(1);
    }

    // =========================================================================
    // Private helpers — report
    // =========================================================================

    private ContributionResult aliceReport() {
        return requestReport(List.of(projectId), List.of(aliceId));
    }

    private ContributionResult aliceReport(List<Long> projectIds) {
        return requestReport(projectIds, List.of(aliceId));
    }

    private ContributionResult requestReport(List<Long> projectIds,
                                             List<Long> userIds) {
        ContributionReportRequest req = new ContributionReportRequest(
            projectIds, userIds,
            PeriodType.CUSTOM, PERIOD_START, PERIOD_END,
            GroupBy.USER, null
        );
        ResponseEntity<ContributionReportResponse> resp = restTemplate.exchange(
            "/api/v1/reports/contributions",
            HttpMethod.POST,
            new HttpEntity<>(req, authHeaders()),
            ContributionReportResponse.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().results().getFirst();
    }

    // =========================================================================
    // Private helpers — metric extraction
    // =========================================================================

    private int intMetric(ContributionResult r,
                          String key) {
        return ((Number) r.metrics().get(key)).intValue();
    }

    private double dblMetric(ContributionResult r,
                             String key) {
        return ((Number) r.metrics().get(key)).doubleValue();
    }

    private Double nullableMetric(ContributionResult r,
                                  String key) {
        Object v = r.metrics().get(key);
        return v == null
            ? null
            : ((Number) v).doubleValue();
    }

    private long seqNext() {
        return SEQ.incrementAndGet();
    }

    // =========================================================================
    // Private helpers — entity builders
    // =========================================================================

    private MergeRequest saveMergedMrByAlice(Instant mergedAt) {
        return saveMergedMrByAlice(mergedAt.minusSeconds(3600), mergedAt);
    }

    private MergeRequest saveMergedMrByAlice(Instant createdAt,
                                             Instant mergedAt) {
        long id = seqNext();
        return mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(id).gitlabMrIid(id)
            .state(MrState.MERGED)
            .authorGitlabUserId(ALICE_GITLAB_ID).authorUsername("alice")
            .createdAtGitlab(createdAt).mergedAtGitlab(mergedAt)
            .mergedByGitlabUserId(ALICE_GITLAB_ID)
            .build());
    }

    private MergeRequest saveMergedMrByBob(Instant mergedAt) {
        long id = seqNext();
        return mrRepository.save(MergeRequest.builder()
            .trackedProjectId(projectId)
            .gitlabMrId(id).gitlabMrIid(id)
            .state(MrState.MERGED)
            .authorGitlabUserId(BOB_GITLAB_ID).authorUsername("bob")
            .createdAtGitlab(mergedAt.minusSeconds(3600)).mergedAtGitlab(mergedAt)
            .mergedByGitlabUserId(BOB_GITLAB_ID)
            .build());
    }

    private MergeRequestCommit saveCommitByAlice(long mrId,
                                                 int additions,
                                                 int deletions,
                                                 Instant authoredDate) {
        return commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mrId)
            .gitlabCommitSha("sha-a-" + seqNext())
            .authorEmail(ALICE_EMAIL).authorName("Alice")
            .authoredDate(authoredDate)
            .additions(additions).deletions(deletions).totalChanges(additions + deletions)
            .build());
    }

    private MergeRequestCommit saveCommitByBob(long mrId,
                                               int additions,
                                               int deletions,
                                               Instant authoredDate) {
        return commitRepository.save(MergeRequestCommit.builder()
            .mergeRequestId(mrId)
            .gitlabCommitSha("sha-b-" + seqNext())
            .authorEmail("bob@example.com").authorName("Bob")
            .authoredDate(authoredDate)
            .additions(additions).deletions(deletions).totalChanges(additions + deletions)
            .build());
    }

    private MergeRequestNote saveNoteByAlice(long mrId,
                                             boolean system,
                                             Instant at) {
        return noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mrId).gitlabNoteId(seqNext())
            .authorGitlabUserId(ALICE_GITLAB_ID).authorUsername("alice")
            .body("comment").system(system).internal(false).createdAtGitlab(at)
            .build());
    }

    private MergeRequestNote saveNoteByBob(long mrId,
                                           boolean system,
                                           Instant at) {
        return noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mrId).gitlabNoteId(seqNext())
            .authorGitlabUserId(BOB_GITLAB_ID).authorUsername("bob")
            .body("review").system(system).internal(false).createdAtGitlab(at)
            .build());
    }

    private MergeRequestNote saveThreadedNote(long mrId,
                                              long discussionId,
                                              long authorId,
                                              Instant at) {
        return noteRepository.save(MergeRequestNote.builder()
            .mergeRequestId(mrId).discussionId(discussionId).gitlabNoteId(seqNext())
            .authorGitlabUserId(authorId)
            .authorUsername(authorId == ALICE_GITLAB_ID
                ? "alice"
                : "bob")
            .body("thread").system(false).internal(false).createdAtGitlab(at)
            .build());
    }

    private MergeRequestDiscussion saveDiscussion(long mrId) {
        return discussionRepository.save(MergeRequestDiscussion.builder()
            .mergeRequestId(mrId)
            .gitlabDiscussionId("disc-" + seqNext())
            .individualNote(false)
            .build());
    }

    private MergeRequestApproval saveApprovalByAlice(long mrId,
                                                     Instant at) {
        return approvalRepository.save(MergeRequestApproval.builder()
            .mergeRequestId(mrId)
            .approvedByGitlabUserId(ALICE_GITLAB_ID).approvedByUsername("alice")
            .approvedAtGitlab(at)
            .build());
    }

    private MergeRequestApproval saveApprovalByBob(long mrId,
                                                   Instant at) {
        return approvalRepository.save(MergeRequestApproval.builder()
            .mergeRequestId(mrId)
            .approvedByGitlabUserId(BOB_GITLAB_ID).approvedByUsername("bob")
            .approvedAtGitlab(at)
            .build());
    }
}
