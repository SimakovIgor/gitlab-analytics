package io.simakov.analytics.web.dto;

public record MrSummaryDto(
    String title,
    String projectPath,
    String webUrl,
    String createdAt,
    String mergedAt,
    Double hoursToMerge
) {

}
