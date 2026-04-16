package io.simakov.analytics.api.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("PMD.JUnitTestContainsTooManyAsserts")
class SyncControllerTest extends BaseIT {

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Test
    void startManualSyncReturns202AndCreatesJob() {
        TrackedProject project = createTrackedProject();

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            true, true, true
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobId()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(SyncStatus.STARTED);

        assertThat(syncJobRepository.findById(response.getBody().jobId())).isPresent();
    }

    @Test
    void startManualSyncReturns401WithoutToken() {
        ManualSyncRequest request = new ManualSyncRequest(
            List.of(1L),
            Instant.now().minus(7, ChronoUnit.DAYS),
            Instant.now(),
            true, true, true
        );

        ResponseEntity<Void> response = restTemplate.postForEntity(
            "/api/v1/sync/manual",
            request,
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void startManualSyncReturns400WhenProjectIdsEmpty() {
        ManualSyncRequest request = new ManualSyncRequest(
            List.of(),
            Instant.now().minus(7, ChronoUnit.DAYS),
            Instant.now(),
            true, true, true
        );

        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getJobStatusReturnsJobForExistingId() {
        TrackedProject project = createTrackedProject();

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project.getId()),
            Instant.now().minus(7, ChronoUnit.DAYS),
            Instant.now(),
            false, false, false
        );

        ResponseEntity<SyncJobResponse> created = restTemplate.exchange(
            "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );

        Long jobId = created.getBody().jobId();

        ResponseEntity<SyncJobResponse> status = restTemplate.exchange(
            "/api/v1/sync/jobs/" + jobId,
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            SyncJobResponse.class
        );

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody().jobId()).isEqualTo(jobId);
    }

    @Test
    void getJobStatusReturns404ForUnknownJobId() {
        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/v1/sync/jobs/99999",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private TrackedProject createTrackedProject() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .name("test-source")
            .baseUrl("https://git.example.com")
            .tokenEncrypted("test-token")
            .build());

        return trackedProjectRepository.save(TrackedProject.builder()
            .gitSourceId(source.getId())
            .gitlabProjectId(42L)
            .pathWithNamespace("team/repo")
            .name("repo")
            .enabled(true)
            .build());
    }
}
