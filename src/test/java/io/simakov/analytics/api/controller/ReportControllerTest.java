package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.ContributionReportRequest;
import io.simakov.analytics.api.dto.response.ContributionReportResponse;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.GroupBy;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.GitSourceRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
class ReportControllerTest extends BaseIT {

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    private Long projectId;
    private Long userId;

    @BeforeEach
    void setUpData() {
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

        TrackedUser user = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice")
            .email("alice@example.com")
            .enabled(true)
            .build());
        userId = user.getId();

        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId)
            .gitlabUserId(100L)
            .username("alice")
            .email("alice@example.com")
            .build());
    }

    @Test
    void contributionsReturnsEmptyResultsWhenNoMrsExist() {
        ContributionReportRequest request = new ContributionReportRequest(
            List.of(projectId),
            List.of(userId),
            PeriodType.LAST_30_DAYS,
            null,
            null,
            GroupBy.USER,
            null
        );

        ResponseEntity<ContributionReportResponse> response = restTemplate.exchange(
            "/api/v1/reports/contributions",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            ContributionReportResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ContributionReportResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.period().preset()).isEqualTo(PeriodType.LAST_30_DAYS);
        assertThat(body.results()).hasSize(1);
        assertThat(body.results().getFirst().displayName()).isEqualTo("Alice");
        assertThat(body.summary().totalMrsMerged()).isZero();
    }

    @Test
    void contributionsReturns401WithoutToken() {
        ContributionReportRequest request = new ContributionReportRequest(
            List.of(projectId),
            List.of(userId),
            PeriodType.LAST_30_DAYS,
            null, null,
            GroupBy.USER,
            null
        );

        ResponseEntity<Void> response = restTemplate.postForEntity(
            "/api/v1/reports/contributions",
            request,
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void contributionsReturns400ForCustomPeriodWithoutDates() {
        ContributionReportRequest request = new ContributionReportRequest(
            List.of(projectId),
            List.of(userId),
            PeriodType.CUSTOM,
            null, null, // missing dateFrom/dateTo
            GroupBy.USER,
            null
        );

        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/v1/reports/contributions",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void contributionsReturns400WhenUserIdsEmpty() {
        ContributionReportRequest request = new ContributionReportRequest(
            List.of(projectId),
            List.of(),
            PeriodType.LAST_30_DAYS,
            null, null,
            GroupBy.USER,
            null
        );

        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/v1/reports/contributions",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
