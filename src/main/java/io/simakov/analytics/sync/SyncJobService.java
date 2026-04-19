package io.simakov.analytics.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final SyncJobRepository syncJobRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SyncJob create(Long workspaceId,
                          ManualSyncRequest request) {
        SyncJob job = SyncJob.builder()
            .workspaceId(workspaceId)
            .status(SyncStatus.STARTED)
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
    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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
