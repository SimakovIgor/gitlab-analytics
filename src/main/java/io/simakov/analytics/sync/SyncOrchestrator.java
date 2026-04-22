package io.simakov.analytics.sync;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.exception.GitLabApiException;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabMergeRequestDto;
import io.simakov.analytics.gitlab.mapper.GitLabMapper;
import io.simakov.analytics.jira.JiraIncidentSyncService;
import io.simakov.analytics.snapshot.SnapshotService;
import io.simakov.analytics.sync.step.SyncContext;
import io.simakov.analytics.sync.step.SyncStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrator {

    private static final int BACKFILL_DAYS = 360;

    private final GitLabApiClient gitLabApiClient;
    private final GitLabMapper gitLabMapper;
    private final EncryptionService encryptionService;

    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final MergeRequestRepository mergeRequestRepository;

    private final SyncJobService syncJobService;
    private final MrAuthorDiscoveryService authorDiscoveryService;
    private final SnapshotService snapshotService;
    private final ReleaseSyncService releaseSyncService;
    private final JiraIncidentSyncService jiraIncidentSyncService;
    private final List<SyncStep> syncSteps;

    @Qualifier("mrProcessingExecutor")
    private final Executor mrProcessingExecutor;

    @Async("syncTaskExecutor")
    public void orchestrateAsync(Long jobId,
                                 ManualSyncRequest request,
                                 SyncJobPhase phase) {
        doOrchestrate(jobId, request, phase);
    }

    /**
     * Core sync logic — always runs synchronously in the caller's thread.
     * Chain methods call this directly; {@link #orchestrateAsync} delegates here via @Async.
     */
    private void doOrchestrate(Long jobId,
                               ManualSyncRequest request,
                               SyncJobPhase phase) {
        log.info("Starting sync job {} (phase={}) for projects {} from {} to {}",
            jobId, phase, request.projectIds(), request.dateFrom(), request.dateTo());
        try {
            for (Long projectId : request.projectIds()) {
                try {
                    syncProject(jobId, projectId, request);
                } catch (Exception e) {
                    log.error("Sync job {} failed for project {}: {}", jobId, projectId, e.getMessage(), e);
                }
            }
            syncJobService.complete(jobId);

            if (phase == SyncJobPhase.FAST) {
                chainEnrichmentPhase(jobId, request);
            } else if (phase == SyncJobPhase.ENRICH) {
                Long workspaceId = syncJobService.findById(jobId).getWorkspaceId();
                int linked = authorDiscoveryService.syncCommitEmails(request.projectIds());
                log.info("ENRICH phase complete for job {}: linked {} commit email(s), triggering snapshot backfill", jobId, linked);
                snapshotService.runDailyBackfillAsync(workspaceId, BACKFILL_DAYS);
                chainReleasePhase(jobId, request);
            }
        } catch (Exception e) {
            log.error("Sync job {} failed with error: {}", jobId, e.getMessage(), e);
            syncJobService.fail(jobId, e.getMessage());
        }
    }

    /**
     * Runs the RELEASE phase for specific projects as a tracked SyncJob.
     * Reads projectIds from the job's payload, syncs releases per project.
     * After completion, auto-chains to JIRA_INCIDENTS phase.
     */
    @Async("syncTaskExecutor")
    public void orchestrateReleaseAsync(Long jobId) {
        doOrchestrateRelease(jobId);
    }

    private void doOrchestrateRelease(Long jobId) {
        log.info("Starting sync job {} (phase=RELEASE)", jobId);
        try {
            ManualSyncRequest request = syncJobService.getPayload(jobId);
            for (Long projectId : request.projectIds()) {
                TrackedProject project = trackedProjectRepository.findById(projectId).orElse(null);
                if (project == null) {
                    log.warn("RELEASE job {}: project {} not found, skipping", jobId, projectId);
                    continue;
                }
                try {
                    releaseSyncService.syncReleasesForProject(project);
                } catch (Exception e) {
                    log.error("RELEASE job {}: failed for project '{}': {}", jobId, project.getName(), e.getMessage(), e);
                }
            }
            syncJobService.complete(jobId);
            log.info("RELEASE phase complete for job {}", jobId);
            chainJiraIncidentPhase(jobId, request);
        } catch (Exception e) {
            log.error("Sync job {} (RELEASE) failed: {}", jobId, e.getMessage(), e);
            syncJobService.fail(jobId, e.getMessage());
        }
    }

    /**
     * Runs the JIRA_INCIDENTS phase as a tracked SyncJob.
     * Fetches Jira incidents and links them to tracked projects by component name.
     */
    @Async("syncTaskExecutor")
    public void orchestrateJiraIncidentsAsync(Long jobId,
                                              int days) {
        doOrchestrateJiraIncidents(jobId, days);
    }

    private void doOrchestrateJiraIncidents(Long jobId,
                                            int days) {
        log.info("Starting sync job {} (phase=JIRA_INCIDENTS, days={})", jobId, days);
        try {
            Long workspaceId = syncJobService.findById(jobId).getWorkspaceId();
            int persisted = jiraIncidentSyncService.syncIncidentsForWorkspace(workspaceId, days);
            syncJobService.complete(jobId);
            log.info("JIRA_INCIDENTS phase complete for job {}: {} links persisted", jobId, persisted);
        } catch (Exception e) {
            log.error("Sync job {} (JIRA_INCIDENTS) failed: {}", jobId, e.getMessage(), e);
            syncJobService.fail(jobId, e.getMessage());
        }
    }

    /**
     * After RELEASE phase: start JIRA_INCIDENTS phase.
     * Workspace-level idempotency: only one JIRA_INCIDENTS job at a time.
     */
    private void chainJiraIncidentPhase(Long releaseJobId,
                                        ManualSyncRequest request) {
        try {
            Long workspaceId = syncJobService.findById(releaseJobId).getWorkspaceId();
            if (syncJobService.findActiveJiraIncidentJob(workspaceId).isPresent()) {
                log.info("JIRA_INCIDENTS phase already running for workspace {}, skipping chain from job {}",
                    workspaceId, releaseJobId);
                return;
            }
            SyncJob jiraJob = syncJobService.createJiraIncidentJob(workspaceId, request);
            syncJobService.linkToNext(releaseJobId, jiraJob.getId());
            log.info("Auto-starting JIRA_INCIDENTS phase as job {} after RELEASE job {}", jiraJob.getId(), releaseJobId);
            doOrchestrateJiraIncidents(jiraJob.getId(), BACKFILL_DAYS);
        } catch (Exception e) {
            log.error("Failed to start JIRA_INCIDENTS phase after job {}: {}", releaseJobId, e.getMessage(), e);
        }
    }

    /**
     * After ENRICH phase: start RELEASE phase as an independent tracked job per project set.
     * Per-project idempotency: skips if a RELEASE job for the same projects is already running.
     */
    private void chainReleasePhase(Long enrichJobId,
                                   ManualSyncRequest enrichRequest) {
        try {
            Long workspaceId = syncJobService.findById(enrichJobId).getWorkspaceId();
            if (syncJobService.findActiveReleaseJobForProjects(workspaceId, enrichRequest.projectIds()).isPresent()) {
                log.info("RELEASE phase already running for projects {}, skipping chain from job {}",
                    enrichRequest.projectIds(), enrichJobId);
                return;
            }
            SyncJob releaseJob = syncJobService.createReleaseJob(workspaceId, enrichRequest);
            syncJobService.linkToNext(enrichJobId, releaseJob.getId());
            log.info("Auto-starting RELEASE phase as job {} for projects {} after ENRICH job {}",
                releaseJob.getId(), enrichRequest.projectIds(), enrichJobId);
            doOrchestrateRelease(releaseJob.getId());
        } catch (Exception e) {
            log.error("Failed to start RELEASE phase after job {}: {}", enrichJobId, e.getMessage(), e);
        }
    }

    /**
     * After FAST phase: auto-discover authors from MR data, then start ENRICH phase.
     * Both run in the background — user is already on the dashboard by now.
     */
    private void chainEnrichmentPhase(Long fastJobId,
                                      ManualSyncRequest fastRequest) {
        try {
            Long workspaceId = syncJobService.findById(fastJobId).getWorkspaceId();
            int discovered = authorDiscoveryService.discoverAndSave(workspaceId, fastRequest.projectIds());
            log.info("Phase 1 complete for job {}: auto-discovered {} author(s)", fastJobId, discovered);

            ManualSyncRequest enrichRequest = new ManualSyncRequest(
                fastRequest.projectIds(), fastRequest.dateFrom(), fastRequest.dateTo(),
                true, true, true, true
            );
            var enrichJob = syncJobService.create(workspaceId, enrichRequest, SyncJobPhase.ENRICH);
            syncJobService.linkToNext(fastJobId, enrichJob.getId());
            log.info("Auto-starting ENRICH phase as job {}", enrichJob.getId());
            doOrchestrate(enrichJob.getId(), enrichRequest, SyncJobPhase.ENRICH);
        } catch (Exception e) {
            log.error("Failed to start ENRICH phase after job {}: {}", fastJobId, e.getMessage(), e);
        }
    }

    private void syncProject(Long jobId,
                             Long trackedProjectId,
                             ManualSyncRequest request) {
        TrackedProject project = trackedProjectRepository.findById(trackedProjectId)
            .orElseThrow(() -> new IllegalArgumentException("TrackedProject not found: " + trackedProjectId));

        GitSource source = gitSourceRepository.findById(project.getGitSourceId())
            .orElseThrow(() -> new IllegalArgumentException("GitSource not found: " + project.getGitSourceId()));

        String projectPath = project.getPathWithNamespace();
        SyncContext ctx = new SyncContext(
            source.getBaseUrl(),
            encryptionService.decrypt(project.getTokenEncrypted()),
            project.getGitlabProjectId()
        );

        log.info("Syncing project '{}' (gitlabId={})", projectPath, project.getGitlabProjectId());

        List<GitLabMergeRequestDto> mrDtos = gitLabApiClient.getMergeRequests(
            ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), request.dateFrom(), request.dateTo());

        int total = mrDtos.size();
        log.info("Fetched {} MRs for project '{}'", total, projectPath);
        syncJobService.updateProgress(jobId, 0, total);

        AtomicInteger processed = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);

        for (GitLabMergeRequestDto mrDto : mrDtos) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    syncMergeRequest(mrDto, trackedProjectId, ctx, request);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null
                        ? e.getCause()
                        : e;
                    if (cause instanceof GitLabApiException glEx && glEx.getStatusCode() != null
                        && glEx.getStatusCode().value() == 429) {
                        log.warn("Rate limit (429) syncing MR iid={} in project {} — retries exhausted",
                            mrDto.iid(), projectPath);
                    } else {
                        log.warn("Failed to sync MR iid={} in project {}: {}",
                            mrDto.iid(), projectPath, cause.getMessage());
                    }
                }
                syncJobService.updateProgress(jobId, processed.incrementAndGet(), total);
            }, mrProcessingExecutor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void syncMergeRequest(GitLabMergeRequestDto mrDto,
                                  Long trackedProjectId,
                                  SyncContext ctx,
                                  ManualSyncRequest request) {
        MergeRequest mr = mergeRequestRepository
            .findByTrackedProjectIdAndGitlabMrId(trackedProjectId, mrDto.id())
            .orElse(null);

        if (mr == null) {
            mr = gitLabMapper.toMergeRequest(mrDto, trackedProjectId);
        } else {
            gitLabMapper.updateMergeRequest(mr, mrDto);
        }
        mr = mergeRequestRepository.save(mr);

        for (SyncStep step : syncSteps) {
            if (step.isEnabled(request)) {
                step.sync(ctx, mr);
            }
        }
    }
}
