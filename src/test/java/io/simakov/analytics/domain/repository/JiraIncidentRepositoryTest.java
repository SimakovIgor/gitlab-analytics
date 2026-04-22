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

    // ── countIncidentsInPeriod ─────────────────────────────────────────────

    @Test
    void countIncidentsInPeriod_returnsCountWithinWindow() {
        saveIncident("MI-1", projectId, baseTime.minus(5, ChronoUnit.DAYS));
        saveIncident("MI-2", projectId, baseTime.minus(10, ChronoUnit.DAYS));
        // outside window
        saveIncident("MI-3", projectId, baseTime.minus(60, ChronoUnit.DAYS));

        long count = jiraIncidentRepository.countIncidentsInPeriod(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countIncidentsInPeriod_returnsZeroWhenNoIncidents() {
        long count = jiraIncidentRepository.countIncidentsInPeriod(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(count).isZero();
    }

    @Test
    void countIncidentsInPeriod_filtersToRequestedProjects() {
        saveIncident("MI-1", projectId, baseTime.minus(5, ChronoUnit.DAYS));
        saveIncident("MI-2", projectId2, baseTime.minus(5, ChronoUnit.DAYS));

        long countP1 = jiraIncidentRepository.countIncidentsInPeriod(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));
        long countBoth = jiraIncidentRepository.countIncidentsInPeriod(
            List.of(projectId, projectId2), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(countP1).isEqualTo(1);
        assertThat(countBoth).isEqualTo(2);
    }

    @Test
    void countIncidentsInPeriod_sameIncidentMultipleProjects() {
        // One Jira issue linked to two projects (different components)
        saveIncident("MI-10", projectId, baseTime.minus(3, ChronoUnit.DAYS));
        saveIncident("MI-10", projectId2, baseTime.minus(3, ChronoUnit.DAYS));

        long count = jiraIncidentRepository.countIncidentsInPeriod(
            List.of(projectId, projectId2), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(count).isEqualTo(2);
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

    // ── countIncidentsByWeek ──────────────────────────────────────────────

    @Test
    void countIncidentsByWeek_groupsIntoWeeklyBuckets() {
        // week 1: 2 incidents
        saveIncident("MI-1", projectId, baseTime.minus(14, ChronoUnit.DAYS));
        saveIncident("MI-2", projectId, baseTime.minus(13, ChronoUnit.DAYS));
        // week 2: 1 incident
        saveIncident("MI-3", projectId, baseTime.minus(3, ChronoUnit.DAYS));

        List<IncidentWeekProjection> rows = jiraIncidentRepository.countIncidentsByWeek(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        long total = rows.stream().mapToLong(IncidentWeekProjection::getIncidentCount).sum();
        assertThat(total).isEqualTo(3);
    }

    @Test
    void countIncidentsByWeek_returnsEmptyWhenNoIncidents() {
        List<IncidentWeekProjection> rows = jiraIncidentRepository.countIncidentsByWeek(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(rows).isEmpty();
    }

    @Test
    void countIncidentsByWeek_weekLabelsFollowIsoFormat() {
        saveIncident("MI-1", projectId, baseTime.minus(3, ChronoUnit.DAYS));

        List<IncidentWeekProjection> rows = jiraIncidentRepository.countIncidentsByWeek(
            List.of(projectId), baseTime.minus(30, ChronoUnit.DAYS));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getWeekLabel()).matches("\\d{4}-W\\d{2}");
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
