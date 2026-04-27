package io.simakov.analytics.sync;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.DoraDeployEventRepository;
import io.simakov.analytics.domain.repository.DoraServiceRepository;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.dora.model.DeployStatus;
import io.simakov.analytics.gitlab.dto.GitLabPipelineDto;
import io.simakov.analytics.gitlab.dto.GitLabPipelineJobDto;
import io.simakov.analytics.gitlab.dto.GitLabReleaseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ReleaseSyncService} writes to the universal dora_deploy_event table
 * (via the GitLab adapter in {@link io.simakov.analytics.dora.DoraEventService}) after
 * syncing releases with a prod::deploy job.
 */
class ReleaseSyncDoraAdapterTest extends BaseIT {

    @Autowired
    private ReleaseSyncService releaseSyncService;

    @Autowired
    private GitSourceRepository gitSourceRepository;

    @Autowired
    private TrackedProjectRepository trackedProjectRepository;

    @Autowired
    private DoraDeployEventRepository doraDeployEventRepository;

    @Autowired
    private DoraServiceRepository doraServiceRepository;

    private TrackedProject project;
    private final Instant deployedAt = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp() {
        GitSource source = gitSourceRepository.save(GitSource.builder()
            .workspaceId(testWorkspaceId)
            .name("test-gl")
            .baseUrl("https://git.example.com")
            .build());

        project = trackedProjectRepository.save(TrackedProject.builder()
            .workspaceId(testWorkspaceId)
            .gitSourceId(source.getId())
            .gitlabProjectId(42L)
            .pathWithNamespace("org/checkout")
            .name("checkout")
            .tokenEncrypted("tok")
            .enabled(true)
            .build());
    }

    @Test
    void syncWithProdDeployJobWritesToDoraDeployEvent() {
        GitLabReleaseDto release = new GitLabReleaseDto("v2.0.0", deployedAt, deployedAt, null);
        GitLabPipelineDto pipeline = new GitLabPipelineDto(100L, "v2.0.0", "success", "abc123");
        GitLabPipelineJobDto prodJob = new GitLabPipelineJobDto(
            200L, "prod::deploy::k8s", "prod", "success", deployedAt);

        when(gitLabApiClient.getReleases(anyString(), anyString(), anyLong()))
            .thenReturn(List.of(release));
        when(gitLabApiClient.getPipelinesForRef(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(List.of(pipeline));
        when(gitLabApiClient.getPipelineJobs(anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(prodJob));
        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), anyLong(), any(), any()))
            .thenReturn(List.of());

        releaseSyncService.syncReleasesForProject(project);

        // dora_deploy_event must have been written
        assertThat(doraDeployEventRepository.count()).isEqualTo(1);
        doraDeployEventRepository.findAll().forEach(e -> {
            assertThat(e.getStatus()).isEqualTo(DeployStatus.SUCCESS);
            assertThat(e.getVersion()).isEqualTo("v2.0.0");
            assertThat(e.getDeployedAt()).isEqualTo(deployedAt);
            assertThat(e.getWorkspaceId()).isEqualTo(testWorkspaceId);
        });
    }

    @Test
    void syncWithoutProdDeployJobDoesNotWriteToDoraDeployEvent() {
        // Pipeline job exists but is not a prod::deploy job
        GitLabReleaseDto release = new GitLabReleaseDto("v1.0.0", deployedAt, deployedAt, null);
        GitLabPipelineDto pipeline = new GitLabPipelineDto(101L, "v1.0.0", "success", "def456");
        GitLabPipelineJobDto testJob = new GitLabPipelineJobDto(
            201L, "test::unit", "test", "success", deployedAt);

        when(gitLabApiClient.getReleases(anyString(), anyString(), anyLong()))
            .thenReturn(List.of(release));
        when(gitLabApiClient.getPipelinesForRef(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(List.of(pipeline));
        when(gitLabApiClient.getPipelineJobs(anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(testJob));
        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), anyLong(), any(), any()))
            .thenReturn(List.of());

        releaseSyncService.syncReleasesForProject(project);

        assertThat(doraDeployEventRepository.count()).isEqualTo(0);
    }

    @Test
    void syncIsIdempotentForSameRelease() {
        GitLabReleaseDto release = new GitLabReleaseDto("v3.0.0", deployedAt, deployedAt, null);
        GitLabPipelineDto pipeline = new GitLabPipelineDto(102L, "v3.0.0", "success", "ghi789");
        GitLabPipelineJobDto prodJob = new GitLabPipelineJobDto(
            202L, "prod::deploy::eks", "prod", "success", deployedAt);

        when(gitLabApiClient.getReleases(anyString(), anyString(), anyLong()))
            .thenReturn(List.of(release));
        when(gitLabApiClient.getPipelinesForRef(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(List.of(pipeline));
        when(gitLabApiClient.getPipelineJobs(anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(prodJob));
        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), anyLong(), any(), any()))
            .thenReturn(List.of());

        releaseSyncService.syncReleasesForProject(project);
        releaseSyncService.syncReleasesForProject(project);

        // Idempotent — still only 1 event
        assertThat(doraDeployEventRepository.count()).isEqualTo(1);
    }

    @Test
    void syncCreatesDoraServiceAndMapping() {
        GitLabReleaseDto release = new GitLabReleaseDto("v4.0.0", deployedAt, deployedAt, null);
        GitLabPipelineDto pipeline = new GitLabPipelineDto(103L, "v4.0.0", "success", "jkl012");
        GitLabPipelineJobDto prodJob = new GitLabPipelineJobDto(
            203L, "prod::deploy::main", "prod", "success", deployedAt);

        when(gitLabApiClient.getReleases(anyString(), anyString(), anyLong()))
            .thenReturn(List.of(release));
        when(gitLabApiClient.getPipelinesForRef(anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(List.of(pipeline));
        when(gitLabApiClient.getPipelineJobs(anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(prodJob));
        when(gitLabApiClient.getMergeRequests(anyString(), anyString(), anyLong(), any(), any()))
            .thenReturn(List.of());

        releaseSyncService.syncReleasesForProject(project);

        assertThat(doraServiceRepository.findByWorkspaceIdAndNameIgnoreCase(testWorkspaceId, "checkout"))
            .isPresent();
    }
}
