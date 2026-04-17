package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;
import java.util.List;

/**
 * Request to manually trigger a metric snapshot run.
 * All fields are optional — defaults are applied from application config.
 * Snapshots always use MERGED_IN_PERIOD mode.
 */
public record RunSnapshotRequest(
    List<Long> userIds,
    List<Long> projectIds,
    @Min(1) @Max(365) Integer windowDays,
    LocalDate snapshotDate
) {

}
