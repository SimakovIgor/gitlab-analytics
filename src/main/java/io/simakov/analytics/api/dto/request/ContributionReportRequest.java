package io.simakov.analytics.api.dto.request;

import io.simakov.analytics.domain.model.enums.GroupBy;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ReportMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record ContributionReportRequest(
    @NotEmpty List<Long> projectIds,
    @NotEmpty List<Long> userIds,
    @NotNull PeriodType periodPreset,
    /** Required when periodPreset = CUSTOM */
    Instant dateFrom,
    /** Required when periodPreset = CUSTOM */
    Instant dateTo,
    @NotNull GroupBy groupBy,
    @NotNull ReportMode reportMode,
    /** If null or empty, all metrics are returned */
    List<String> metrics
) {

}
