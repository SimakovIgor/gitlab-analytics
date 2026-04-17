package io.simakov.analytics.metrics;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.model.UserMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("PMD:JUnitTestContainsTooManyAsserts")
class MetricCalculationServiceTest {

    private static final Long TRACKED_USER_ID = 1L;
    private static final Long GITLAB_USER_ID = 100L;
    private static final Long PROJECT_ID = 10L;
    @Mock
    MergeRequestRepository mrRepository;
    @Mock
    MergeRequestNoteRepository noteRepository;
    @Mock
    MergeRequestApprovalRepository approvalRepository;
    @Mock
    MergeRequestCommitRepository commitRepository;
    @Mock
    TrackedUserRepository trackedUserRepository;
    @Mock
    TrackedUserAliasRepository aliasRepository;
    @InjectMocks
    MetricCalculationService service;
    private TrackedUser user;
    private TrackedUserAlias alias;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now();
        user = TrackedUser.builder()
            .id(TRACKED_USER_ID)
            .displayName("Alice")
            .email("alice@example.com")
            .enabled(true)
            .build();
        alias = TrackedUserAlias.builder()
            .id(1L)
            .trackedUserId(TRACKED_USER_ID)
            .gitlabUserId(GITLAB_USER_ID)
            .build();
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
    void shouldCalculateDeliveryMetrics() {
        MergeRequest mr1 = mergedMr(1L, 101L, now.minus(20, ChronoUnit.DAYS));
        MergeRequest mr2 = mergedMr(2L, 102L, now.minus(10, ChronoUnit.DAYS));

        // Commits are the source of lines_added/deleted (not MR-level additions/deletions)
        MergeRequestCommit commit1 = MergeRequestCommit.builder()
            .id(1L).mergeRequestId(1L).gitlabCommitSha("sha1")
            .authorEmail("alice@example.com")
            .authoredDate(now.minus(19, ChronoUnit.DAYS))
            .additions(50).deletions(10).totalChanges(60)
            .build();
        MergeRequestCommit commit2 = MergeRequestCommit.builder()
            .id(2L).mergeRequestId(2L).gitlabCommitSha("sha2")
            .authorEmail("alice@example.com")
            .authoredDate(now.minus(9, ChronoUnit.DAYS))
            .additions(50).deletions(10).totalChanges(60)
            .build();

        when(trackedUserRepository.findById(TRACKED_USER_ID)).thenReturn(Optional.of(user));
        when(aliasRepository.findByTrackedUserIdIn(anyList())).thenReturn(List.of(alias));
        when(mrRepository.findMergedInPeriod(anyList(), any(), any())).thenReturn(List.of(mr1, mr2));
        when(noteRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(approvalRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(commitRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of(commit1, commit2));

        Map<Long, UserMetrics> result = service.calculate(
            List.of(PROJECT_ID), List.of(TRACKED_USER_ID),
            now.minus(30, ChronoUnit.DAYS), now);

        assertThat(result).containsKey(TRACKED_USER_ID);
        UserMetrics metrics = result.get(TRACKED_USER_ID);
        assertThat(metrics.getMrOpenedCount()).isEqualTo(2);
        assertThat(metrics.getMrMergedCount()).isEqualTo(2);
        assertThat(metrics.getLinesAdded()).isEqualTo(100);
        assertThat(metrics.getLinesDeleted()).isEqualTo(20);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
    void shouldCalculateReviewMetrics() {
        // A MR authored by someone else
        MergeRequest foreignMr = mergedMr(3L, 200L, now.minus(5, ChronoUnit.DAYS));
        foreignMr.setAuthorGitlabUserId(999L); // different user

        // A review comment by our user on the foreign MR
        MergeRequestNote reviewNote = MergeRequestNote.builder()
            .id(1L)
            .mergeRequestId(3L)
            .gitlabNoteId(1001L)
            .authorGitlabUserId(GITLAB_USER_ID)
            .system(false)
            .createdAtGitlab(now.minus(4, ChronoUnit.DAYS))
            .build();

        when(trackedUserRepository.findById(TRACKED_USER_ID)).thenReturn(Optional.of(user));
        when(aliasRepository.findByTrackedUserIdIn(anyList())).thenReturn(List.of(alias));
        when(mrRepository.findMergedInPeriod(anyList(), any(), any())).thenReturn(List.of(foreignMr));
        when(noteRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of(reviewNote));
        when(approvalRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(commitRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());

        Map<Long, UserMetrics> result = service.calculate(
            List.of(PROJECT_ID), List.of(TRACKED_USER_ID),
            now.minus(30, ChronoUnit.DAYS), now);

        UserMetrics metrics = result.get(TRACKED_USER_ID);
        assertThat(metrics.getReviewCommentsWrittenCount()).isEqualTo(1);
        assertThat(metrics.getMrsReviewedCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
    void shouldNotCountSystemNotesAsReviews() {
        MergeRequest foreignMr = mergedMr(3L, 200L, now.minus(5, ChronoUnit.DAYS));
        foreignMr.setAuthorGitlabUserId(999L);

        // System note (e.g. "added 1 commit") — should NOT count
        MergeRequestNote systemNote = MergeRequestNote.builder()
            .id(2L)
            .mergeRequestId(3L)
            .gitlabNoteId(1002L)
            .authorGitlabUserId(GITLAB_USER_ID)
            .system(true)
            .createdAtGitlab(now.minus(4, ChronoUnit.DAYS))
            .build();

        when(trackedUserRepository.findById(TRACKED_USER_ID)).thenReturn(Optional.of(user));
        when(aliasRepository.findByTrackedUserIdIn(anyList())).thenReturn(List.of(alias));
        when(mrRepository.findMergedInPeriod(anyList(), any(), any())).thenReturn(List.of(foreignMr));
        when(noteRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of(systemNote));
        when(approvalRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(commitRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());

        Map<Long, UserMetrics> result = service.calculate(
            List.of(PROJECT_ID), List.of(TRACKED_USER_ID),
            now.minus(30, ChronoUnit.DAYS), now);

        UserMetrics metrics = result.get(TRACKED_USER_ID);
        assertThat(metrics.getReviewCommentsWrittenCount()).isEqualTo(0);
        assertThat(metrics.getMrsReviewedCount()).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
    void shouldDetectRework() {
        MergeRequest mr = mergedMr(1L, 101L, now.minus(10, ChronoUnit.DAYS));

        // First external review at -8 days
        MergeRequestNote reviewNote = MergeRequestNote.builder()
            .id(1L)
            .mergeRequestId(1L)
            .gitlabNoteId(200L)
            .authorGitlabUserId(999L) // different user
            .system(false)
            .createdAtGitlab(now.minus(8, ChronoUnit.DAYS))
            .build();

        // Author pushes a commit AFTER first review at -6 days → rework
        MergeRequestCommit reworkCommit = MergeRequestCommit.builder()
            .id(1L)
            .mergeRequestId(1L)
            .gitlabCommitSha("abc123")
            .authorEmail("alice@example.com")
            .authoredDate(now.minus(6, ChronoUnit.DAYS))
            .build();

        when(trackedUserRepository.findById(TRACKED_USER_ID)).thenReturn(Optional.of(user));
        when(aliasRepository.findByTrackedUserIdIn(anyList())).thenReturn(List.of(alias));
        when(mrRepository.findMergedInPeriod(anyList(), any(), any())).thenReturn(List.of(mr));
        when(noteRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of(reviewNote));
        when(approvalRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(commitRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of(reworkCommit));

        Map<Long, UserMetrics> result = service.calculate(
            List.of(PROJECT_ID), List.of(TRACKED_USER_ID),
            now.minus(30, ChronoUnit.DAYS), now);

        UserMetrics metrics = result.get(TRACKED_USER_ID);
        assertThat(metrics.getReworkMrCount()).isEqualTo(1);
        assertThat(metrics.getReworkRatio()).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
    void shouldCalculateTimeToFirstReview() {
        MergeRequest mr = mergedMr(1L, 101L, now.minus(10, ChronoUnit.DAYS));

        // External review 2 days after MR creation → 2 * 24 * 60 = 2880 minutes
        MergeRequestNote reviewNote = MergeRequestNote.builder()
            .id(1L)
            .mergeRequestId(1L)
            .gitlabNoteId(300L)
            .authorGitlabUserId(999L)
            .system(false)
            .createdAtGitlab(now.minus(8, ChronoUnit.DAYS))
            .build();

        when(trackedUserRepository.findById(TRACKED_USER_ID)).thenReturn(Optional.of(user));
        when(aliasRepository.findByTrackedUserIdIn(anyList())).thenReturn(List.of(alias));
        when(mrRepository.findMergedInPeriod(anyList(), any(), any())).thenReturn(List.of(mr));
        when(noteRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of(reviewNote));
        when(approvalRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(commitRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());

        Map<Long, UserMetrics> result = service.calculate(
            List.of(PROJECT_ID), List.of(TRACKED_USER_ID),
            now.minus(30, ChronoUnit.DAYS), now);

        UserMetrics metrics = result.get(TRACKED_USER_ID);
        assertThat(metrics.getAvgTimeToFirstReviewMinutes()).isNotNull();
        assertThat(metrics.getAvgTimeToFirstReviewMinutes()).isEqualTo(2880.0);
    }

    @Test
    @SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
    void shouldReturnEmptyMetricsForUserWithNoAliases() {
        when(trackedUserRepository.findById(TRACKED_USER_ID)).thenReturn(Optional.of(user));
        when(aliasRepository.findByTrackedUserIdIn(anyList())).thenReturn(List.of()); // no aliases
        when(mrRepository.findMergedInPeriod(anyList(), any(), any())).thenReturn(List.of());
        when(noteRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(approvalRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());
        when(commitRepository.findByMergeRequestIdIn(anyList())).thenReturn(List.of());

        Map<Long, UserMetrics> result = service.calculate(
            List.of(PROJECT_ID), List.of(TRACKED_USER_ID),
            now.minus(30, ChronoUnit.DAYS), now);

        UserMetrics metrics = result.get(TRACKED_USER_ID);
        assertThat(metrics.getMrOpenedCount()).isEqualTo(0);
        assertThat(metrics.getMrMergedCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------

    private MergeRequest mergedMr(Long id,
                                  Long gitlabId,
                                  Instant createdAt) {
        MergeRequest mr = new MergeRequest();
        mr.setId(id);
        mr.setTrackedProjectId(PROJECT_ID);
        mr.setGitlabMrId(gitlabId);
        mr.setGitlabMrIid(gitlabId);
        mr.setState(MrState.MERGED);
        mr.setAuthorGitlabUserId(GITLAB_USER_ID);
        mr.setCreatedAtGitlab(createdAt);
        mr.setMergedAtGitlab(createdAt.plus(2, ChronoUnit.DAYS));
        mr.setAdditions(50);
        mr.setDeletions(10);
        return mr;
    }
}
