package io.simakov.analytics.domain.repository;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.MrState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MergeRequestRepositoryTest extends BaseIT {

    @Autowired
    private MergeRequestRepository mergeRequestRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    private Long projectId;
    private Instant baseTime;

    @BeforeEach
    void setUpProject() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-source")
            .baseUrl("https://git.example.com")
            .build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(42L)
            .pathWithNamespace("team/repo")
            .name("repo")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        projectId = project.getId();
        baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void findMergedInPeriodReturnsOnlyMergedMrsWithinWindow() {
        saveMr(10L, baseTime.minus(40, ChronoUnit.DAYS), baseTime.minus(5, ChronoUnit.DAYS));
        saveMr(11L, baseTime.minus(10, ChronoUnit.DAYS), null); // not merged

        Instant from = baseTime.minus(30, ChronoUnit.DAYS);
        List<MergeRequest> result = mergeRequestRepository.findMergedInPeriod(
            List.of(projectId), from, baseTime);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getGitlabMrId()).isEqualTo(10L);
    }

    @Test
    void upsertUpdatesExistingMrWithoutCreatingDuplicate() {
        MergeRequest first = saveMr(20L, baseTime.minus(5, ChronoUnit.DAYS), null);
        Long firstDbId = first.getId();

        // Simulate upsert: find → update → save
        MergeRequest found = mergeRequestRepository
            .findByTrackedProjectIdAndGitlabMrId(projectId, 20L)
            .orElseThrow();
        found.setState(MrState.MERGED);
        found.setMergedAtGitlab(baseTime);
        mergeRequestRepository.save(found);

        Optional<MergeRequest> updated = mergeRequestRepository
            .findByTrackedProjectIdAndGitlabMrId(projectId, 20L);

        assertThat(updated).isPresent();
        assertThat(updated.get().getId()).isEqualTo(firstDbId);
        assertThat(updated.get().getState()).isEqualTo(MrState.MERGED);
        assertThat(updated.get().getMergedAtGitlab()).isEqualTo(baseTime);
        assertThat(mergeRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void findByTrackedProjectIdAndGitlabMrIdReturnsEmptyForUnknownMr() {
        Optional<MergeRequest> result = mergeRequestRepository
            .findByTrackedProjectIdAndGitlabMrId(projectId, 99_999L);

        assertThat(result).isEmpty();
    }

    // ── Lead Time queries ────────────────────────────────────────────────────

    @Test
    void findLeadTimeSummaryReturnsCorrectCountAndMedian() {
        // 2 h lead time
        saveMergedMrWithLeadTime(1L, 2);
        // 4 h lead time
        saveMergedMrWithLeadTime(2L, 4);
        // opened MR — must be excluded
        saveMr(3L, baseTime.minus(1, ChronoUnit.DAYS), null);

        Instant dateFrom = baseTime.minus(30, ChronoUnit.DAYS);
        List<Object[]> rows = mergeRequestRepository.findLeadTimeSummary(List.of(projectId), dateFrom);

        assertThat(rows).hasSize(1);
        Object[] row = rows.getFirst();
        assertThat(((Number) row[0]).intValue()).isEqualTo(2);
        // median of [2, 4] = 3.0
        assertThat(((Number) row[1]).doubleValue()).isCloseTo(3.0, within(0.1));
    }

    @Test
    void findLeadTimeSummaryReturnsEmptyWhenNoMergedMrs() {
        saveMr(1L, baseTime.minus(5, ChronoUnit.DAYS), null);

        List<Object[]> rows = mergeRequestRepository.findLeadTimeSummary(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(rows).hasSize(1);
        // COUNT = 0, percentiles are null when no rows match
        assertThat(((Number) rows.getFirst()[0]).intValue()).isZero();
    }

    @Test
    void findLeadTimeSummaryExcludesMrsOutsideDateRange() {
        saveMergedMrWithLeadTime(1L, 2);
        // merged 60 days ago — outside 30-day window
        MergeRequest old = saveMr(2L, baseTime.minus(62, ChronoUnit.DAYS), null);
        old.setState(MrState.MERGED);
        old.setMergedAtGitlab(baseTime.minus(60, ChronoUnit.DAYS));
        mergeRequestRepository.save(old);

        List<Object[]> rows = mergeRequestRepository.findLeadTimeSummary(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(((Number) rows.getFirst()[0]).intValue()).isEqualTo(1);
    }

    @Test
    void findLeadTimeSummaryFiltersToRequestedProjects() {
        GitSource source2 = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId).name("other").baseUrl("https://other.com").build());
        TrackedProject project2 = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId).gitSourceId(source2.getId()).gitlabProjectId(99L)
            .pathWithNamespace("other/repo").name("other-repo")
            .tokenEncrypted("tok").enabled(true).build());

        saveMergedMrWithLeadTime(1L, 3);
        // MR in project2 — should not appear when filtering by projectId only
        MergeRequest other = new MergeRequest();
        other.setTrackedProjectId(project2.getId());
        other.setGitlabMrId(99L);
        other.setGitlabMrIid(99L);
        other.setState(MrState.MERGED);
        other.setCreatedAtGitlab(baseTime.minus(5, ChronoUnit.DAYS));
        other.setMergedAtGitlab(baseTime.minus(1, ChronoUnit.DAYS));
        other.setAuthorGitlabUserId(200L);
        mergeRequestRepository.save(other);

        List<Object[]> rows = mergeRequestRepository.findLeadTimeSummary(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(((Number) rows.getFirst()[0]).intValue()).isEqualTo(1);
    }

    @Test
    void findLeadTimeByWeekGroupsMrsIntoWeeklyBuckets() {
        // week 1: 2 MRs
        saveMergedMrAtTime(1L, baseTime.minus(14, ChronoUnit.DAYS), 2);
        saveMergedMrAtTime(2L, baseTime.minus(13, ChronoUnit.DAYS), 4);
        // week 2: 1 MR
        saveMergedMrAtTime(3L, baseTime.minus(3, ChronoUnit.DAYS), 6);

        List<Object[]> rows = mergeRequestRepository.findLeadTimeByWeek(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        // total across all weeks
        int total = rows.stream().mapToInt(r -> ((Number) r[1]).intValue()).sum();
        assertThat(total).isEqualTo(3);
    }

    @Test
    void findLeadTimeByWeekExcludesOpenedMrs() {
        saveMergedMrWithLeadTime(1L, 3);
        saveMr(2L, baseTime.minus(5, ChronoUnit.DAYS), null); // opened

        List<Object[]> rows = mergeRequestRepository.findLeadTimeByWeek(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        int total = rows.stream().mapToInt(r -> ((Number) r[1]).intValue()).sum();
        assertThat(total).isEqualTo(1);
    }

    /**
     * Saves a MERGED MR with given lead time in hours, merged at baseTime.
     */
    private void saveMergedMrWithLeadTime(Long gitlabMrId,
                                          int leadHours) {
        saveMergedMrAtTime(gitlabMrId, baseTime, leadHours);
    }

    private void saveMergedMrAtTime(Long gitlabMrId,
                                    Instant mergedAt,
                                    int leadHours) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(gitlabMrId);
        mr.setGitlabMrIid(gitlabMrId);
        mr.setState(MrState.MERGED);
        mr.setCreatedAtGitlab(mergedAt.minus(leadHours, ChronoUnit.HOURS));
        mr.setMergedAtGitlab(mergedAt);
        mr.setAuthorGitlabUserId(100L);
        mergeRequestRepository.save(mr);
    }

    private MergeRequest saveMr(Long gitlabMrId,
                                Instant createdAt,
                                Instant mergedAt) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(projectId);
        mr.setGitlabMrId(gitlabMrId);
        mr.setGitlabMrIid(gitlabMrId);
        mr.setState(mergedAt != null
            ? MrState.MERGED
            : MrState.OPENED);
        mr.setCreatedAtGitlab(createdAt);
        mr.setMergedAtGitlab(mergedAt);
        mr.setAuthorGitlabUserId(100L);
        return mergeRequestRepository.save(mr);
    }
}
