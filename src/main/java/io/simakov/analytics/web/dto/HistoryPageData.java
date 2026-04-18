package io.simakov.analytics.web.dto;

import java.time.LocalDate;
import java.util.Map;

public record HistoryPageData(
    String chartJson,
    String selectedMetric,
    String selectedPeriod,
    String metricLabel,
    Map<String, String> metricOptions,
    LocalDate dateFrom,
    LocalDate dateTo
) {

}
