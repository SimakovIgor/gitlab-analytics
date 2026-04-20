package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.dto.response.SyncJobResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
@Tag(name = "Sync",
     description = "Trigger and monitor data synchronization")
public class SyncController {

    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final TrackedProjectRepository trackedProjectRepository;

    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a manual sync for selected projects",
               description = "Starts an async sync job and returns the job ID for polling.")
    public SyncJobResponse startManualSync(@RequestBody @Valid ManualSyncRequest request) {
        Long workspaceId = WorkspaceContext.get();
        Set<Long> workspaceProjectIds = trackedProjectRepository.findAllByWorkspaceId(workspaceId)
            .stream().map(TrackedProject::getId).collect(Collectors.toSet());
        for (Long pid : request.projectIds()) {
            if (!workspaceProjectIds.contains(pid)) {
                throw new ResourceNotFoundException("TrackedProject", pid);
            }
        }
        // Idempotency: return the running job instead of starting a duplicate
        var activeJob = syncJobService.findActiveJobForProjects(workspaceId, request.projectIds());
        if (activeJob.isPresent()) {
            log.info("Sync already running (job {}), returning existing job", activeJob.get().getId());
            return SyncJobResponse.from(activeJob.get());
        }

        SyncJob job = syncJobService.create(workspaceId, request, SyncJobPhase.ENRICH);
        log.info("Created sync job {} for {} projects", job.getId(), request.projectIds().size());
        syncOrchestrator.orchestrateAsync(job.getId(), request, SyncJobPhase.ENRICH);

        return SyncJobResponse.from(job);
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get sync job status")
    public SyncJobResponse getJobStatus(@PathVariable Long jobId) {
        SyncJob job = syncJobService.findById(jobId);
        if (!WorkspaceContext.get().equals(job.getWorkspaceId())) {
            throw new ResourceNotFoundException("SyncJob", jobId);
        }
        return SyncJobResponse.from(job);
    }
}
