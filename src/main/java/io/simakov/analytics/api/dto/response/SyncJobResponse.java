package io.simakov.analytics.api.dto.response;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.enums.SyncStatus;

import java.time.Instant;

public record SyncJobResponse(
    Long jobId,
    SyncStatus status,
    Instant startedAt,
    Instant finishedAt,
    Instant dateFrom,
    Instant dateTo,
    String errorMessage,
    int totalMrs,
    int processedMrs
) {

    public static SyncJobResponse from(SyncJob job) {
        return new SyncJobResponse(job.getId(), job.getStatus(), job.getStartedAt(),
            job.getFinishedAt(), job.getDateFrom(), job.getDateTo(), job.getErrorMessage(),
            job.getTotalMrs(), job.getProcessedMrs());
    }
}
