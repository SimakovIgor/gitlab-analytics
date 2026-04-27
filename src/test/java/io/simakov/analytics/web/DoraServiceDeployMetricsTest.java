package io.simakov.analytics.web;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.DoraDeployEvent;
import io.simakov.analytics.domain.model.DoraIncidentEvent;
import io.simakov.analytics.domain.model.DoraServiceMapping;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.DoraDeployEventRepository;
import io.simakov.analytics.domain.repository.DoraIncidentEventRepository;
import io.simakov.analytics.domain.repository.DoraServiceMappingRepository;
import io.simakov.analytics.domain.repository.DoraServiceRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.dora.model.DeploySource;
import io.simakov.analytics.dora.model.DeployStatus;
import io.simakov.analytics.dora.model.DoraRating;
import io.simakov.analytics.dora.model.IncidentSource;
import io.simakov.analytics.security.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DoraService#buildDeployFrequencyData} and
 * {@link DoraService#buildMttrData}, both of which read from the universal
 * dora_deploy_event / dora_incident_event tables.
 */
class DoraServiceDeployMetricsTest extends BaseIT {

    @Autowired
    private DoraService doraService;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private DoraServiceRepository doraServiceRepository;

    @Autowired
    private DoraServiceMappingRepository doraServiceMappingRepository;

    @Autowired
    private DoraDeployEventRepository doraDeployEventRepository;

    @Autowired
    private DoraIncidentEventRepository doraIncidentEventRepository;

    private Long projectId;
    private Long doraServiceId;
    private Instant baseTime;

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(testWorkspaceId);
        baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-source")
            .baseUrl("https://git.example.com")
            .build());

        TrackedProject project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(1L)
            .pathWithNamespace("org/api")
            .name("api")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        projectId = project.getId();

        io.simakov.analytics.domain.model.DoraService svc =
            doraServiceRepository.save(io.simakov.analytics.domain.model.DoraService.builder()
                .workspaceId(testWorkspaceId)
                .name("api")
                .build());
        doraServiceId = svc.getId();

        doraServiceMappingRepository.save(DoraServiceMapping.builder()
            .doraServiceId(doraServiceId)
            .sourceType("GITLAB")
            .sourceKey(String.valueOf(projectId))
            .build());
    }

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    // ── Deploy Frequency ──────────────────────────────────────────────────

    @Test
    void deployFrequencyReturnsZeroWhenNoEvents() {
        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(projectId), 30);

        assertThat(result.get("totalDeploys")).isEqualTo(0L);
        assertThat((Double) result.get("deploysPerDay")).isEqualTo(0.0);
        assertThat(result.get("deployFreqRating")).isEqualTo(DoraRating.NO_DATA);
    }

    @Test
    void deployFrequencyCountsOnlySuccessfulDeploys() {
        saveDeploy("v1.0", baseTime.minus(5, ChronoUnit.DAYS), DeployStatus.SUCCESS);
        saveDeploy("v1.1", baseTime.minus(3, ChronoUnit.DAYS), DeployStatus.FAILED);
        saveDeploy("v1.2", baseTime.minus(1, ChronoUnit.DAYS), DeployStatus.SUCCESS);

        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(projectId), 30);

        assertThat(result.get("totalDeploys")).isEqualTo(2L);
    }

    @Test
    void deployFrequencyExcludesDataOutsidePeriod() {
        saveDeploy("v-old", baseTime.minus(60, ChronoUnit.DAYS), DeployStatus.SUCCESS);
        saveDeploy("v-new", baseTime.minus(5, ChronoUnit.DAYS), DeployStatus.SUCCESS);

        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(projectId), 30);

        assertThat(result.get("totalDeploys")).isEqualTo(1L);
    }

    @Test
    void deployFrequencyRatingEliteForDailyDeploys() {
        // 2 deploys/day × 7 days = 14 deploys → 14/7 = 2.0/day >> ELITE threshold (1.0)
        for (int i = 0; i < 14; i++) {
            saveDeploy("v1." + i, baseTime.minus(i * 12, ChronoUnit.HOURS), DeployStatus.SUCCESS);
        }

        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(projectId), 7);

        assertThat(result.get("deployFreqRating")).isEqualTo(DoraRating.ELITE);
    }

    @Test
    void deployFrequencyRatingLowForInfrequentDeploy() {
        // 1 deploy in a 90-day window → 1/90 ≈ 0.011/day < MEDIUM threshold (1/30) → LOW
        saveDeploy("v1", baseTime.minus(15, ChronoUnit.DAYS), DeployStatus.SUCCESS);

        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(projectId), 90);

        assertThat(result.get("deployFreqRating")).isEqualTo(DoraRating.LOW);
    }

    @Test
    void deployFrequencyChartJsonContainsLabelsAndDatasets() {
        saveDeploy("v1", baseTime.minus(5, ChronoUnit.DAYS), DeployStatus.SUCCESS);

        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(projectId), 30);

        String chartJson = (String) result.get("chartJson");
        assertThat(chartJson).contains("labels").contains("datasets").contains("Деплои");
    }

    @Test
    void deployFrequencyReturnsEmptyChartWhenNoMapping() {
        // project without DoraServiceMapping → serviceIds empty → no events
        GitSource src2 = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId).name("src2").baseUrl("https://other.com").build());
        TrackedProject unmapped = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId).gitSourceId(src2.getId())
            .gitlabProjectId(99L).pathWithNamespace("org/unmapped").name("unmapped")
            .tokenEncrypted("tok").enabled(true).build());

        Map<String, Object> result = doraService.buildDeployFrequencyData(List.of(unmapped.getId()), 30);

        assertThat(result.get("totalDeploys")).isEqualTo(0L);
        assertThat(result.get("deployFreqRating")).isEqualTo(DoraRating.NO_DATA);
    }

    // ── MTTR ──────────────────────────────────────────────────────────────

    @Test
    void mttrReturnsNullWhenNoIncidents() {
        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat(result.get("mttrHours")).isNull();
        assertThat(result.get("totalIncidents")).isEqualTo(0L);
        assertThat(result.get("mttrRating")).isEqualTo(DoraRating.NO_DATA);
    }

    @Test
    void mttrExcludesUnresolvedIncidents() {
        // Only unresolved — MTTR should be null
        saveIncident("INC-1", baseTime.minus(5, ChronoUnit.DAYS), null);

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat(result.get("mttrHours")).isNull();
        assertThat(result.get("totalIncidents")).isEqualTo(0L);
    }

    @Test
    void mttrExcludesInvalidDuration() {
        // resolved_at <= started_at — should be excluded (negative MTTR)
        Instant start = baseTime.minus(5, ChronoUnit.DAYS);
        saveIncident("INC-bad", start, start.minusSeconds(3600)); // resolved BEFORE started

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat(result.get("mttrHours")).isNull();
        assertThat(result.get("totalIncidents")).isEqualTo(0L);
    }

    @Test
    void mttrCalculatesAverageCorrectly() {
        // 2h incident + 4h incident → avg 3h
        Instant t1 = baseTime.minus(10, ChronoUnit.DAYS);
        Instant t2 = baseTime.minus(5, ChronoUnit.DAYS);
        saveIncident("INC-1", t1, t1.plus(2, ChronoUnit.HOURS));
        saveIncident("INC-2", t2, t2.plus(4, ChronoUnit.HOURS));

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat((Double) result.get("mttrHours")).isEqualTo(3.0);
        assertThat(result.get("totalIncidents")).isEqualTo(2L);
    }

    @Test
    void mttrRatingEliteForFastRecovery() {
        // 0.5h MTTR → ELITE (< 1h)
        Instant t = baseTime.minus(5, ChronoUnit.DAYS);
        saveIncident("INC-fast", t, t.plus(30, ChronoUnit.MINUTES));

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat(result.get("mttrRating")).isEqualTo(DoraRating.ELITE);
    }

    @Test
    void mttrRatingLowForSlowRecovery() {
        // 200h MTTR → LOW (> 1 week)
        Instant t = baseTime.minus(20, ChronoUnit.DAYS);
        saveIncident("INC-slow", t, t.plus(200, ChronoUnit.HOURS));

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat(result.get("mttrRating")).isEqualTo(DoraRating.LOW);
    }

    @Test
    void mttrExcludesDataOutsidePeriod() {
        Instant old = baseTime.minus(60, ChronoUnit.DAYS);
        saveIncident("INC-old", old, old.plus(2, ChronoUnit.HOURS));

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        assertThat(result.get("mttrHours")).isNull();
    }

    @Test
    void mttrChartJsonContainsExpectedFields() {
        Instant t = baseTime.minus(5, ChronoUnit.DAYS);
        saveIncident("INC-chart", t, t.plus(2, ChronoUnit.HOURS));

        Map<String, Object> result = doraService.buildMttrData(List.of(projectId), 30);

        String chartJson = (String) result.get("chartJson");
        assertThat(chartJson).contains("labels").contains("jiraKeys").contains("values");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void saveDeploy(String version, Instant deployedAt, DeployStatus status) {
        doraDeployEventRepository.save(DoraDeployEvent.builder()
            .workspaceId(testWorkspaceId)
            .doraServiceId(doraServiceId)
            .environment("production")
            .deployedAt(deployedAt)
            .status(status)
            .source(DeploySource.GITLAB_TAGS)
            .version(version)
            .idempotencyKey("test-" + version + "-" + deployedAt.toEpochMilli())
            .build());
    }

    private void saveIncident(String externalId, Instant startedAt, Instant resolvedAt) {
        doraIncidentEventRepository.save(DoraIncidentEvent.builder()
            .workspaceId(testWorkspaceId)
            .doraServiceId(doraServiceId)
            .startedAt(startedAt)
            .resolvedAt(resolvedAt)
            .source(IncidentSource.JIRA)
            .idempotencyKey("test-inc-" + externalId)
            .externalId(externalId)
            .build());
    }
}
