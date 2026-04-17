package io.simakov.analytics.api.dto.response;

import io.simakov.analytics.domain.model.enums.PeriodType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ContributionReportResponse(
    PeriodInfo period,
    SummaryInfo summary,
    List<ContributionResult> results
) {

    public record PeriodInfo(
        Instant from,
        Instant to,
        PeriodType preset
    ) {

    }

    public record SummaryInfo(
        int totalMrsMerged,
        int totalReviewComments,
        int totalApprovals,
        int usersIncluded
    ) {

    }

    public record ContributionResult(
        Long userId,
        String displayName,
        Long projectId,
        String projectName,
        Map<String, Object> metrics,
        Map<String, Double> normalized,
        Map<String, Double> teamComparison
    ) {

    }
}
