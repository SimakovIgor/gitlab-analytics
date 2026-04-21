package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.TrackedProject;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MetricChartData(
    String chartJson,
    String selectedMetric,
    String selectedPeriod,
    String metricLabel,
    Map<String, String> metricOptions,
    LocalDate dateFrom,
    LocalDate dateTo,
    List<TrackedProject> allProjects,
    List<Long> selectedProjectIds,
    boolean showInactive
) {

}
