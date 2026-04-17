package io.simakov.analytics.api.dto.request;

import io.simakov.analytics.domain.model.enums.ReportMode;

import java.time.LocalDate;
import java.util.List;

/**
 * Request to manually trigger a metric snapshot run.
 * All fields are optional — defaults are applied by the service.
 */
public record RunSnapshotRequest(
    List<Long> userIds,
    List<Long> projectIds,
    Integer windowDays,
    ReportMode reportMode,
    LocalDate snapshotDate
) {

}
