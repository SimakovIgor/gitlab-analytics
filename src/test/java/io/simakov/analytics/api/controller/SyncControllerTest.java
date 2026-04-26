package io.simakov.analytics.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.BaseIT;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.SyncJob;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class SyncControllerTest extends BaseIT {

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void startManualSyncReturns202AndCreatesJob() {
        TrackedProject project = createTrackedProject();

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            true, true, true, false
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
            true, true, true, false
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
            true, true, true, false
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
            false, false, false, false
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

    @Test
    void syncJobCompletedWithErrorsWhenOneProjectFails() throws InterruptedException {
        TrackedProject project1 = createTrackedProjectWithGitlabId(1L);
        TrackedProject project2 = createTrackedProjectWithGitlabId(2L);

        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), eq(1L), any(Instant.class), any(Instant.class)))
            .thenThrow(new RuntimeException("GitLab API error for project 1"));
        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), eq(2L), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project1.getId(), project2.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            false, false, false, false
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long jobId = response.getBody().jobId();

        SyncJob job = awaitJobFinished(jobId);

        assertThat(job.getStatus()).isEqualTo(SyncStatus.COMPLETED_WITH_ERRORS);
        assertThat(job.getErrorMessage()).contains("GitLab API error for project 1");
    }

    @Test
    void syncJobFailsWhenAllProjectsFail() throws InterruptedException {
        TrackedProject project1 = createTrackedProjectWithGitlabId(1L);

        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), eq(1L), any(Instant.class), any(Instant.class)))
            .thenThrow(new RuntimeException("Token revoked"));

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project1.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            false, false, false, false
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long jobId = response.getBody().jobId();

        SyncJob job = awaitJobFinished(jobId);

        assertThat(job.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("Token revoked");
    }

    @Test
    void syncJobCompletesWhenAllProjectsSucceed() throws InterruptedException {
        TrackedProject project1 = createTrackedProjectWithGitlabId(1L);

        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), eq(1L), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project1.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            false, false, false, false
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long jobId = response.getBody().jobId();

        SyncJob job = awaitJobFinished(jobId);

        assertThat(job.getStatus()).isEqualTo(SyncStatus.COMPLETED);
        assertThat(job.getErrorMessage()).isNull();
    }

    // ── Idempotency: duplicate sync protection ────────────────────────────────

    @Test
    void startManualSync_whenActiveJobExists_returnsExistingJobWithoutCreatingDuplicate() {
        TrackedProject project = createTrackedProject();
        SyncJob existingJob = seedStartedJob(List.of(project.getId()));

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            true, true, true, false
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().jobId()).isEqualTo(existingJob.getId());
        assertThat(response.getBody().status()).isEqualTo(SyncStatus.STARTED);
        assertThat(syncJobRepository.count()).isEqualTo(1);
    }

    @Test
    void startManualSync_whenActiveJobExistsForOverlappingProject_returnsExistingJob() {
        TrackedProject project1 = createTrackedProjectWithGitlabId(1L);
        TrackedProject project2 = createTrackedProjectWithGitlabId(2L);
        TrackedProject project3 = createTrackedProjectWithGitlabId(3L);
        // Running job covers project1 + project2
        SyncJob existingJob = seedStartedJob(List.of(project1.getId(), project2.getId()));

        // New request overlaps on project2
        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project2.getId(), project3.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            false, false, false, false
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().jobId()).isEqualTo(existingJob.getId());
        assertThat(syncJobRepository.count()).isEqualTo(1);
    }

    @Test
    void startManualSync_whenOnlyCompletedJobExists_createsNewJob() {
        TrackedProject project = createTrackedProject();
        seedCompletedJob(List.of(project.getId()));

        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), any(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());

        ManualSyncRequest request = new ManualSyncRequest(
            List.of(project.getId()),
            Instant.now().minus(30, ChronoUnit.DAYS),
            Instant.now(),
            false, false, false, false
        );

        ResponseEntity<SyncJobResponse> response = restTemplate.exchange(
            "/api/v1/sync/manual",
            HttpMethod.POST,
            new HttpEntity<>(request, authHeaders()),
            SyncJobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo(SyncStatus.STARTED);
        assertThat(syncJobRepository.count()).isEqualTo(2); // old completed + new started
    }

    private SyncJob seedStartedJob(List<Long> projectIds) {
        return syncJobRepository.save(SyncJob.builder()
            .workspaceId(testWorkspaceId)
            .status(SyncStatus.STARTED)
            .dateFrom(Instant.now().minus(30, ChronoUnit.DAYS))
            .dateTo(Instant.now())
            .payloadJson(toPayloadJson(projectIds))
            .build());
    }

    private void seedCompletedJob(List<Long> projectIds) {
        syncJobRepository.save(SyncJob.builder()
            .workspaceId(testWorkspaceId)
            .status(SyncStatus.COMPLETED)
            .dateFrom(Instant.now().minus(30, ChronoUnit.DAYS))
            .dateTo(Instant.now())
            .payloadJson(toPayloadJson(projectIds))
            .build());
    }

    private String toPayloadJson(List<Long> projectIds) {
        try {
            ManualSyncRequest payload = new ManualSyncRequest(
                projectIds,
                Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now(),
                false, false, false, false
            );
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize payload", e);
        }
    }

    private SyncJob awaitJobFinished(Long jobId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            SyncJob job = syncJobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() != SyncStatus.STARTED) {
                return job;
            }
        }
        throw new AssertionError("Job " + jobId + " did not finish in time");
    }

    private TrackedProject createTrackedProjectWithGitlabId(Long gitlabProjectId) {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("source-" + gitlabProjectId)
            .baseUrl("https://git.example.com")
            .build());
        return trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(gitlabProjectId)
            .pathWithNamespace("team/repo-" + gitlabProjectId)
            .name("repo-" + gitlabProjectId)
            .tokenEncrypted("test-token")
            .enabled(true)
            .build());
    }

    @Test
    void triggerReleaseSyncReturns202() {
        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sync/releases",
            HttpMethod.POST,
            new HttpEntity<>(null, authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void getLatestReleaseJobReturns404WhenNoJobExists() {
        ResponseEntity<Void> resp = restTemplate.exchange(
            "http://localhost:" + port + "/api/v1/sync/jobs/latest-release",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private TrackedProject createTrackedProject() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-source")
            .baseUrl("https://git.example.com")
            .build());

        return trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(42L)
            .pathWithNamespace("team/repo")
            .name("repo")
            .tokenEncrypted("test-token")
            .enabled(true)
            .build());
    }
}
