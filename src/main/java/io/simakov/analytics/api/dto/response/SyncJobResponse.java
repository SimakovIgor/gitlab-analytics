package io.simakov.analytics.api.dto.response;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
import io.simakov.analytics.domain.model.enums.SyncStatus;

import java.time.Instant;

public record SyncJobResponse(
    Long jobId,
    SyncStatus status,
    SyncJobPhase phase,
    Instant startedAt,
    Instant finishedAt,
    Instant dateFrom,
    Instant dateTo,
    String errorMessage,
    int totalMrs,
    int processedMrs,
    String projectName,
    Long nextJobId
) {

    public static SyncJobResponse from(SyncJob job) {
        return new SyncJobResponse(job.getId(), job.getStatus(), job.getPhase(),
            job.getStartedAt(), job.getFinishedAt(), job.getDateFrom(), job.getDateTo(),
            job.getErrorMessage(), job.getTotalMrs(), job.getProcessedMrs(), null,
            job.getNextJobId());
    }

    public static SyncJobResponse from(SyncJob job,
                                       String projectName) {
        return new SyncJobResponse(job.getId(), job.getStatus(), job.getPhase(),
            job.getStartedAt(), job.getFinishedAt(), job.getDateFrom(), job.getDateTo(),
            job.getErrorMessage(), job.getTotalMrs(), job.getProcessedMrs(), projectName,
            job.getNextJobId());
    }
}
