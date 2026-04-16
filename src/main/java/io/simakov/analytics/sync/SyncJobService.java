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
    public SyncJob create(ManualSyncRequest request) {
        SyncJob job = SyncJob.builder()
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
