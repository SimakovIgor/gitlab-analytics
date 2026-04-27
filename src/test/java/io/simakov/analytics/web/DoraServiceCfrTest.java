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

class DoraServiceCfrTest extends BaseIT {

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
            .pathWithNamespace("org/payment-service")
            .name("payment-service")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());

        projectId = project.getId();

        io.simakov.analytics.domain.model.DoraService svc =
            doraServiceRepository.save(io.simakov.analytics.domain.model.DoraService.builder()
                .workspaceId(testWorkspaceId)
                .name("payment-service")
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

    // ── CFR = incidents / deploys × 100 ───────────────────────────────────

    @Test
    void cfrReturnsNullWhenNoDeploysExist() {
        saveIncident("MI-1", baseTime.minus(5, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat(result.get("cfrPercent")).isNull();
        assertThat(result.get("totalIncidents")).isEqualTo(1L);
        assertThat(result.get("totalDeploys")).isEqualTo(0L);
        assertThat(result.get("cfrRating")).isEqualTo(DoraRating.NO_DATA);
    }

    @Test
    void cfrReturnsZeroWhenDeploysExistButNoIncidents() {
        saveDeploy("v1.0.0", baseTime.minus(10, ChronoUnit.DAYS));
        saveDeploy("v1.1.0", baseTime.minus(5, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat((Double) result.get("cfrPercent")).isEqualTo(0.0);
        assertThat(result.get("totalIncidents")).isEqualTo(0L);
        assertThat(result.get("totalDeploys")).isEqualTo(2L);
    }

    @Test
    void cfrCalculatesCorrectPercentage() {
        // 10 deploys, 2 incidents → 20%
        for (int i = 1; i <= 10; i++) {
            saveDeploy("v1." + i + ".0", baseTime.minus(i, ChronoUnit.DAYS));
        }
        saveIncident("MI-1", baseTime.minus(3, ChronoUnit.DAYS));
        saveIncident("MI-2", baseTime.minus(7, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat((Double) result.get("cfrPercent")).isEqualTo(20.0);
        assertThat(result.get("totalIncidents")).isEqualTo(2L);
        assertThat(result.get("totalDeploys")).isEqualTo(10L);
    }

    @Test
    void cfrRatingEliteWhenBelowFivePercent() {
        // 100 deploys, 3 incidents → 3%
        for (int i = 1; i <= 100; i++) {
            saveDeploy("v" + i, baseTime.minus(i % 29 + 1, ChronoUnit.DAYS));
        }
        saveIncident("MI-1", baseTime.minus(5, ChronoUnit.DAYS));
        saveIncident("MI-2", baseTime.minus(10, ChronoUnit.DAYS));
        saveIncident("MI-3", baseTime.minus(15, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat((Double) result.get("cfrPercent")).isEqualTo(3.0);
        assertThat(result.get("cfrRating")).isEqualTo(DoraRating.ELITE);
    }

    @Test
    void cfrRatingLowWhenAboveFifteenPercent() {
        // 5 deploys, 1 incident → 20%
        for (int i = 1; i <= 5; i++) {
            saveDeploy("v1." + i, baseTime.minus(i, ChronoUnit.DAYS));
        }
        saveIncident("MI-1", baseTime.minus(2, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat((Double) result.get("cfrPercent")).isEqualTo(20.0);
        assertThat(result.get("cfrRating")).isEqualTo(DoraRating.LOW);
    }

    @Test
    void cfrExcludesDataOutsidePeriod() {
        saveDeploy("v-old", baseTime.minus(60, ChronoUnit.DAYS));
        saveIncident("MI-old", baseTime.minus(60, ChronoUnit.DAYS));
        saveDeploy("v-new", baseTime.minus(5, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat(result.get("totalDeploys")).isEqualTo(1L);
        assertThat(result.get("totalIncidents")).isEqualTo(0L);
        assertThat((Double) result.get("cfrPercent")).isEqualTo(0.0);
    }

    @Test
    void cfrChartJsonIsNotEmpty() {
        saveDeploy("v1", baseTime.minus(5, ChronoUnit.DAYS));
        saveIncident("MI-1", baseTime.minus(5, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        String chartJson = (String) result.get("chartJson");
        assertThat(chartJson).isNotEmpty();
        assertThat(chartJson).contains("labels");
        assertThat(chartJson).contains("CFR");
    }

    @Test
    void cfrChartJsonReturnsEmptyObjectWhenNoData() {
        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        String chartJson = (String) result.get("chartJson");
        assertThat(chartJson).isNotNull();
        assertThat(chartJson).contains("labels");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void saveDeploy(String version,
                             Instant deployedAt) {
        doraDeployEventRepository.save(DoraDeployEvent.builder()
            .workspaceId(testWorkspaceId)
            .doraServiceId(doraServiceId)
            .environment("production")
            .deployedAt(deployedAt)
            .status(DeployStatus.SUCCESS)
            .source(DeploySource.GITLAB_TAGS)
            .version(version)
            .idempotencyKey("test-" + version + "-" + deployedAt.toEpochMilli())
            .build());
    }

    private void saveIncident(String externalId,
                               Instant startedAt) {
        doraIncidentEventRepository.save(DoraIncidentEvent.builder()
            .workspaceId(testWorkspaceId)
            .doraServiceId(doraServiceId)
            .startedAt(startedAt)
            .source(IncidentSource.JIRA)
            .idempotencyKey("test-incident-" + externalId)
            .externalId(externalId)
            .build());
    }
}
