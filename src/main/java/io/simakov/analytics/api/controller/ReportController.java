package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.ContributionReportRequest;
import io.simakov.analytics.api.dto.response.ContributionReportResponse;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.Metric;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports",
     description = "Contribution and review analytics reports")
public class ReportController {

    private final MetricCalculationService metricCalculationService;
    private final TrackedProjectRepository trackedProjectRepository;

    @PostMapping("/contributions")
    @Operation(summary = "Calculate contribution report",
               description = "Returns engineering metrics per user for the requested period.")
    public ContributionReportResponse contributions(@RequestBody @Valid ContributionReportRequest request) {
        var period = resolvePeriod(request);
        Instant dateFrom = period[0];
        Instant dateTo = period[1];

        log.info("Generating contribution report for {} users, {} projects, period {} - {}",
            request.userIds().size(), request.projectIds().size(), dateFrom, dateTo);

        Map<Long, UserMetrics> metricsByUser = metricCalculationService.calculate(
            request.projectIds(), request.userIds(), dateFrom, dateTo);

        // Build results
        List<ContributionReportResponse.ContributionResult> results = new ArrayList<>();
        Map<String, List<Double>> teamMetricValues = collectTeamValues(metricsByUser);

        for (Long userId : request.userIds()) {
            UserMetrics m = metricsByUser.get(userId);
            if (m == null) {
                continue;
            }

            Map<String, Object> metricsMap = filterMetrics(m.toMetricsMap(), request.metrics());
            Map<String, Double> normalized = m.toNormalizedMap();
            Map<String, Double> teamComparison = buildTeamComparison(m, teamMetricValues);

            results.add(new ContributionReportResponse.ContributionResult(
                userId, m.getDisplayName(), null, null,
                metricsMap, normalized, teamComparison));
        }

        // Summary
        int totalMerged = results.stream()
            .mapToInt(r -> toInt(r.metrics().get(Metric.MR_MERGED_COUNT.key()))).sum();
        int totalComments = results.stream()
            .mapToInt(r -> toInt(r.metrics().get(Metric.REVIEW_COMMENTS_WRITTEN_COUNT.key()))).sum();
        int totalApprovals = results.stream()
            .mapToInt(r -> toInt(r.metrics().get(Metric.APPROVALS_GIVEN_COUNT.key()))).sum();

        return new ContributionReportResponse(
            new ContributionReportResponse.PeriodInfo(dateFrom, dateTo, request.periodPreset()),
            new ContributionReportResponse.SummaryInfo(totalMerged, totalComments, totalApprovals, results.size()),
            results
        );
    }

    private Instant[] resolvePeriod(ContributionReportRequest request) {
        Instant now = DateTimeUtils.now();
        if (request.periodPreset() == PeriodType.CUSTOM) {
            if (request.dateFrom() == null || request.dateTo() == null) {
                throw new IllegalArgumentException("dateFrom and dateTo are required for CUSTOM period");
            }
            return new Instant[]{request.dateFrom(), request.dateTo()};
        }
        return new Instant[]{DateTimeUtils.minusDays(now, request.periodPreset().toDays()), now};
    }

    private Map<String, Object> filterMetrics(Map<String, Object> all,
                                              List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return all;
        }
        return all.entrySet().stream()
            .filter(e -> requested.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Map<String, List<Double>> collectTeamValues(Map<Long, UserMetrics> metricsByUser) {
        Map<String, List<Double>> teamValues = new HashMap<>();
        for (UserMetrics m : metricsByUser.values()) {
            m.toMetricsMap().forEach((key, value) -> {
                if (value instanceof Number n) {
                    teamValues.computeIfAbsent(key, k -> new ArrayList<>()).add(n.doubleValue());
                }
            });
        }
        return teamValues;
    }

    private Map<String, Double> buildTeamComparison(UserMetrics m,
                                                    Map<String, List<Double>> teamValues) {
        Map<String, Double> comparison = new HashMap<>();
        List<String> keyMetrics = List.of(
            Metric.MR_MERGED_COUNT.key(),
            Metric.REVIEW_COMMENTS_WRITTEN_COUNT.key(),
            Metric.APPROVALS_GIVEN_COUNT.key(),
            Metric.MRS_REVIEWED_COUNT.key()
        );

        for (String key : keyMetrics) {
            Object val = m.toMetricsMap().get(key);
            if (val instanceof Number n) {
                double userVal = n.doubleValue();
                List<Double> teamVals = teamValues.getOrDefault(key, List.of());
                double percentile = calculatePercentile(userVal, teamVals);
                comparison.put(key + "_percentile", percentile);
            }
        }
        return comparison;
    }

    /**
     * Position of userVal within sorted teamVals as a 0-100 percentile
     */
    private double calculatePercentile(double userVal,
                                       List<Double> teamVals) {
        if (teamVals.size() <= 1) {
            return 100.0;
        }
        long below = teamVals.stream().filter(v -> v < userVal).count();
        return Math.round((double) below / (teamVals.size() - 1) * 100.0 * 10) / 10.0;
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}
