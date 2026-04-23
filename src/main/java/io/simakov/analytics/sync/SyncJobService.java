package io.simakov.analytics.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final SyncJobRepository syncJobRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SyncJob create(Long workspaceId,
                          ManualSyncRequest request,
                          SyncJobPhase phase) {
        SyncJob job = SyncJob.builder()
            .workspaceId(workspaceId)
            .status(SyncStatus.STARTED)
            .phase(phase)
            .dateFrom(request.dateFrom())
            .dateTo(request.dateTo())
            .payloadJson(toJson(request))
            .build();
        return syncJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long jobId) {
        SyncJob job = findOrThrow(jobId);
        job.setStatus(SyncStatus.COMPLETED);
        job.setFinishedAt(Instant.now());
        job.setErrorMessage(null);
        syncJobRepository.save(job);
        log.info("Sync job {} completed", jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeWithErrors(Long jobId,
                                   String errorMessage) {
        SyncJob job = findOrThrow(jobId);
        job.setStatus(SyncStatus.COMPLETED_WITH_ERRORS);
        job.setFinishedAt(Instant.now());
        job.setErrorMessage(errorMessage);
        syncJobRepository.save(job);
        log.warn("Sync job {} completed with errors: {}", jobId, errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long jobId,
                     String errorMessage) {
        SyncJob job = findOrThrow(jobId);
        job.setStatus(SyncStatus.FAILED);
        job.setFinishedAt(Instant.now());
        job.setErrorMessage(errorMessage);
        syncJobRepository.save(job);
        log.error("Sync job {} failed: {}", jobId, errorMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(Long jobId,
                               int processed,
                               int total) {
        SyncJob job = findOrThrow(jobId);
        job.setProcessedMrs(processed);
        job.setTotalMrs(total);
        syncJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkToNext(Long currentJobId,
                           Long nextJobId) {
        SyncJob job = findOrThrow(currentJobId);
        job.setNextJobId(nextJobId);
        syncJobRepository.save(job);
    }

    @Transactional
    public int failStaleJobs(Instant startedBefore,
                             String reason) {
        var stale = syncJobRepository.findByStatusAndStartedAtBefore(SyncStatus.STARTED, startedBefore);
        Instant now = Instant.now();
        for (SyncJob job : stale) {
            job.setStatus(SyncStatus.FAILED);
            job.setFinishedAt(now);
            job.setErrorMessage(reason);
        }
        syncJobRepository.saveAll(stale);
        if (!stale.isEmpty()) {
            log.warn("Marked {} stale sync job(s) as FAILED: {}", stale.size(), reason);
        }
        return stale.size();
    }

    /**
     * Reads the original ManualSyncRequest from a job's payloadJson.
     * Used by callers who need the request to pass to the orchestrator.
     */
    public ManualSyncRequest getPayload(Long jobId) {
        SyncJob job = findOrThrow(jobId);
        if (job.getPayloadJson() == null) {
            throw new IllegalStateException("Job " + jobId + " has no payload");
        }
        try {
            return objectMapper.readValue(job.getPayloadJson(), ManualSyncRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse payload of job " + jobId, e);
        }
    }

    /**
     * Returns the first STARTED job in the workspace whose projectIds overlap with the given set.
     * Used to detect duplicate syncs before creating a new job.
     */
    public Optional<SyncJob> findActiveJobForProjects(Long workspaceId,
                                                      Collection<Long> projectIds) {
        Set<Long> requested = Set.copyOf(projectIds);
        List<SyncJob> active = syncJobRepository
            .findByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED);
        return active.stream()
            .filter(job -> {
                if (job.getPayloadJson() == null) {
                    return false;
                }
                try {
                    ManualSyncRequest payload = objectMapper.readValue(job.getPayloadJson(), ManualSyncRequest.class);
                    return payload.projectIds().stream().anyMatch(requested::contains);
                } catch (JsonProcessingException e) {
                    return false;
                }
            })
            .findFirst();
    }

    @Transactional
    public SyncJob createReleaseJob(Long workspaceId,
                                    ManualSyncRequest request) {
        SyncJob job = SyncJob.builder()
            .workspaceId(workspaceId)
            .status(SyncStatus.STARTED)
            .phase(SyncJobPhase.RELEASE)
            .payloadJson(toJson(request))
            .build();
        return syncJobRepository.save(job);
    }

    @Transactional
    public SyncJob createJiraIncidentJob(Long workspaceId,
                                         ManualSyncRequest request) {
        SyncJob job = SyncJob.builder()
            .workspaceId(workspaceId)
            .status(SyncStatus.STARTED)
            .phase(SyncJobPhase.JIRA_INCIDENTS)
            .payloadJson(toJson(request))
            .build();
        return syncJobRepository.save(job);
    }

    /**
     * Returns an active RELEASE job whose projectIds overlap with the given set.
     * Used to prevent starting a duplicate RELEASE for the same project.
     */
    public Optional<SyncJob> findActiveReleaseJobForProjects(Long workspaceId,
                                                             Collection<Long> projectIds) {
        Set<Long> requested = Set.copyOf(projectIds);
        List<SyncJob> active = syncJobRepository
            .findByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED);
        return active.stream()
            .filter(job -> job.getPhase() == SyncJobPhase.RELEASE)
            .filter(job -> {
                if (job.getPayloadJson() == null) {
                    return false;
                }
                try {
                    ManualSyncRequest payload = objectMapper.readValue(job.getPayloadJson(), ManualSyncRequest.class);
                    return payload.projectIds().stream().anyMatch(requested::contains);
                } catch (JsonProcessingException e) {
                    return false;
                }
            })
            .findFirst();
    }

    public List<Long> findActiveReleaseJobIds(Long workspaceId) {
        return syncJobRepository
            .findByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED)
            .stream()
            .filter(j -> j.getPhase() == SyncJobPhase.RELEASE)
            .map(SyncJob::getId)
            .toList();
    }

    public Optional<SyncJob> findActiveEnrichmentJob(Long workspaceId) {
        return syncJobRepository.findTopByWorkspaceIdAndStatusAndPhaseOrderByStartedAtDesc(
            workspaceId, SyncStatus.STARTED, SyncJobPhase.ENRICH);
    }

    public Optional<SyncJob> findActiveJiraIncidentJob(Long workspaceId) {
        return syncJobRepository.findTopByWorkspaceIdAndStatusAndPhaseOrderByStartedAtDesc(
            workspaceId, SyncStatus.STARTED, SyncJobPhase.JIRA_INCIDENTS);
    }

    public List<Long> findActiveJiraJobIds(Long workspaceId) {
        return syncJobRepository
            .findByWorkspaceIdAndStatusOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED)
            .stream()
            .filter(j -> j.getPhase() == SyncJobPhase.JIRA_INCIDENTS)
            .map(SyncJob::getId)
            .toList();
    }

    /**
     * Returns the most recent RELEASE job for the workspace — STARTED first, then COMPLETED.
     * Used by the onboarding page to show Phase 3 progress after Phase 2 finishes.
     */
    public Optional<SyncJob> findLatestReleaseJob(Long workspaceId) {
        return syncJobRepository
            .findTopByWorkspaceIdAndStatusAndPhaseOrderByStartedAtDesc(workspaceId, SyncStatus.STARTED, SyncJobPhase.RELEASE)
            .or(() -> syncJobRepository
                .findTopByWorkspaceIdAndStatusAndPhaseOrderByStartedAtDesc(workspaceId, SyncStatus.COMPLETED, SyncJobPhase.RELEASE));
    }

    public SyncJob findById(Long jobId) {
        return findOrThrow(jobId);
    }

    private SyncJob findOrThrow(Long jobId) {
        return syncJobRepository.findById(jobId)
            .orElseThrow(() -> new ResourceNotFoundException("SyncJob", jobId));
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
