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

@SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
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
            .name("test-source")
            .baseUrl("https://git.example.com")
            .tokenEncrypted("token")
            .build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(source.getId())
            .gitlabProjectId(42L)
            .pathWithNamespace("team/repo")
            .name("repo")
            .enabled(true)
            .build());

        projectId = project.getId();
        baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void findCreatedInPeriodReturnsOnlyMrsWithinWindow() {
        saveMr(1L, baseTime.minus(10, ChronoUnit.DAYS), null);
        saveMr(2L, baseTime.minus(40, ChronoUnit.DAYS), null); // outside window

        Instant from = baseTime.minus(30, ChronoUnit.DAYS);
        List<MergeRequest> result = mergeRequestRepository.findCreatedInPeriod(
            List.of(projectId), from, baseTime);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getGitlabMrId()).isEqualTo(1L);
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
    void findCreatedInPeriodReturnsEmptyWhenNoMrsExist() {
        Instant from = baseTime.minus(30, ChronoUnit.DAYS);
        List<MergeRequest> result = mergeRequestRepository.findCreatedInPeriod(
            List.of(projectId), from, baseTime);

        assertThat(result).isEmpty();
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
