package io.simakov.analytics.web.dto;

import java.util.Map;

public record HistoryPageData(
    String chartJson,
    String selectedMetric,
    int selectedDays,
    String metricLabel,
    Map<String, String> metricOptions
) {

}
