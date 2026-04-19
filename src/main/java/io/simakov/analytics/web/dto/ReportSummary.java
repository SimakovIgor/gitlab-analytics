package io.simakov.analytics.web.dto;

public record ReportSummary(
    int totalMrMerged,
    Integer deltaMrMerged,
    int activeDevs,
    int totalDevs,
    Double medianTimeToMergeHours,
    Double deltaMedianTimeToMergeHours,
    int totalComments,
    Integer deltaComments
) {

}
