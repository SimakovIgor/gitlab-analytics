package io.simakov.analytics.sync;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabMergeRequestDto;
import io.simakov.analytics.gitlab.mapper.GitLabMapper;
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

    private final GitLabApiClient gitLabApiClient;
    private final GitLabMapper gitLabMapper;
    private final EncryptionService encryptionService;

    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final MergeRequestRepository mergeRequestRepository;

    private final SyncJobService syncJobService;
    private final List<SyncStep> syncSteps;

    @Qualifier("mrProcessingExecutor")
    private final Executor mrProcessingExecutor;

    @Async("syncTaskExecutor")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void orchestrateAsync(Long jobId,
                                 ManualSyncRequest request) {
        log.info("Starting sync job {} for projects {} from {} to {}",
            jobId, request.projectIds(), request.dateFrom(), request.dateTo());
        try {
            for (Long projectId : request.projectIds()) {
                syncProject(jobId, projectId, request);
            }
            syncJobService.complete(jobId);
        } catch (Exception e) {
            log.error("Sync job {} failed with error: {}", jobId, e.getMessage(), e);
            syncJobService.fail(jobId, e.getMessage());
        }
    }

    @SuppressWarnings({"checkstyle:IllegalCatch", "PMD.AvoidInstantiatingObjectsInLoops"})
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
                    log.warn("Failed to sync MR iid={} in project {}: {}",
                        mrDto.iid(), projectPath, e.getMessage());
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
