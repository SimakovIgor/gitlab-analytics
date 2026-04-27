package io.simakov.analytics.domain.repository;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.JiraIncident;
import io.simakov.analytics.domain.model.TrackedProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JiraIncidentRepositoryTest extends BaseIT {

    @Autowired
    private JiraIncidentRepository jiraIncidentRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    private Long projectId;
    private Long projectId2;
    private Instant baseTime;

    @BeforeEach
    void setUpProjects() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-source")
            .baseUrl("https://git.example.com")
            .build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/service-a")
            .name("service-a")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        TrackedProject project2 = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(2L)
            .pathWithNamespace("org/service-b")
            .name("service-b")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        projectId = project.getId();
        projectId2 = project2.getId();
        baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    // ── countIncidentsInPeriodBetween ──────────────────────────────────────

    @Test
    void countIncidentsInPeriodBetween_respectsBoundedInterval() {
        saveIncident("MI-1", projectId, baseTime.minus(20, ChronoUnit.DAYS));
        saveIncident("MI-2", projectId, baseTime.minus(5, ChronoUnit.DAYS));

        long count = jiraIncidentRepository.countIncidentsInPeriodBetween(
            List.of(projectId),
            baseTime.minus(30, ChronoUnit.DAYS),
            baseTime.minus(10, ChronoUnit.DAYS));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void countIncidentsInPeriodBetween_returnsZeroWhenEmpty() {
        long count = jiraIncidentRepository.countIncidentsInPeriodBetween(
            List.of(projectId),
            baseTime.minus(30, ChronoUnit.DAYS),
            baseTime);

        assertThat(count).isZero();
    }

    // ── existsByWorkspaceId ────────────────────────────────────────────────

    @Test
    void existsByWorkspaceId_trueWhenIncidentsSynced() {
        saveIncident("MI-1", projectId, baseTime.minus(5, ChronoUnit.DAYS));

        assertThat(jiraIncidentRepository.existsByWorkspaceId(testWorkspaceId)).isTrue();
    }

    @Test
    void existsByWorkspaceId_falseWhenNoIncidents() {
        assertThat(jiraIncidentRepository.existsByWorkspaceId(testWorkspaceId)).isFalse();
    }

    // ── findByJiraKeyAndTrackedProjectId (upsert support) ─────────────────

    @Test
    void findByJiraKeyAndTrackedProjectId_findsExistingIncident() {
        saveIncident("MI-5", projectId, baseTime.minus(2, ChronoUnit.DAYS));

        Optional<JiraIncident> found = jiraIncidentRepository
            .findByJiraKeyAndTrackedProjectId("MI-5", projectId);

        assertThat(found).isPresent();
        assertThat(found.get().getJiraKey()).isEqualTo("MI-5");
    }

    @Test
    void findByJiraKeyAndTrackedProjectId_returnsEmptyForDifferentProject() {
        saveIncident("MI-5", projectId, baseTime.minus(2, ChronoUnit.DAYS));

        Optional<JiraIncident> found = jiraIncidentRepository
            .findByJiraKeyAndTrackedProjectId("MI-5", projectId2);

        assertThat(found).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private JiraIncident saveIncident(String jiraKey,
                                      Long trackedProjectId,
                                      Instant createdAt) {
        return jiraIncidentRepository.save(JiraIncident.builder()
            .workspaceId(testWorkspaceId)
            .trackedProjectId(trackedProjectId)
            .jiraKey(jiraKey)
            .summary("Incident " + jiraKey)
            .createdAt(createdAt)
            .componentName("service-component")
            .build());
    }
}
