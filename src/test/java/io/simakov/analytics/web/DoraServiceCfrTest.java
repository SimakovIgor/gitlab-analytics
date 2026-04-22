package io.simakov.analytics.web;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.JiraIncident;
import io.simakov.analytics.domain.model.ReleaseTag;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.JiraIncidentRepository;
import io.simakov.analytics.domain.repository.ReleaseTagRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.dora.model.DoraRating;
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
    private ReleaseTagRepository releaseTagRepository;

    @Autowired
    private JiraIncidentRepository jiraIncidentRepository;

    private Long projectId;
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
        saveProdDeploy("v1.0.0", baseTime.minus(10, ChronoUnit.DAYS));
        saveProdDeploy("v1.1.0", baseTime.minus(5, ChronoUnit.DAYS));

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
            saveProdDeploy("v1." + i + ".0", baseTime.minus(i, ChronoUnit.DAYS));
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
            saveProdDeploy("v" + i, baseTime.minus(i % 29 + 1, ChronoUnit.DAYS));
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
            saveProdDeploy("v1." + i, baseTime.minus(i, ChronoUnit.DAYS));
        }
        saveIncident("MI-1", baseTime.minus(2, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat((Double) result.get("cfrPercent")).isEqualTo(20.0);
        assertThat(result.get("cfrRating")).isEqualTo(DoraRating.LOW);
    }

    @Test
    void cfrExcludesDataOutsidePeriod() {
        saveProdDeploy("v-old", baseTime.minus(60, ChronoUnit.DAYS));
        saveIncident("MI-old", baseTime.minus(60, ChronoUnit.DAYS));
        saveProdDeploy("v-new", baseTime.minus(5, ChronoUnit.DAYS));

        Map<String, Object> result = doraService.buildChangeFailureRateData(
            List.of(projectId), 30);

        assertThat(result.get("totalDeploys")).isEqualTo(1L);
        assertThat(result.get("totalIncidents")).isEqualTo(0L);
        assertThat((Double) result.get("cfrPercent")).isEqualTo(0.0);
    }

    @Test
    void cfrChartJsonIsNotEmpty() {
        saveProdDeploy("v1", baseTime.minus(5, ChronoUnit.DAYS));
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
        // Should still be valid JSON (may be empty labels or {})
        assertThat(chartJson).contains("labels");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void saveProdDeploy(String tagName,
                                Instant prodDeployedAt) {
        releaseTagRepository.save(ReleaseTag.builder()
            .trackedProjectId(projectId)
            .tagName(tagName)
            .tagCreatedAt(prodDeployedAt)
            .prodDeployedAt(prodDeployedAt)
            .build());
    }

    private void saveIncident(String jiraKey,
                              Instant createdAt) {
        jiraIncidentRepository.save(JiraIncident.builder()
            .workspaceId(testWorkspaceId)
            .trackedProjectId(projectId)
            .jiraKey(jiraKey)
            .summary("Incident " + jiraKey)
            .createdAt(createdAt)
            .componentName("payment-service")
            .build());
    }
}
