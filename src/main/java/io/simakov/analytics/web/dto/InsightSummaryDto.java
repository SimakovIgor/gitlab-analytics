package io.simakov.analytics.web.dto;

import java.util.List;

/**
 * Compact insight representation for the Overview page "Что требует внимания" strip.
 * Contains only the fields needed for the compact InsightCard component.
 */
public record InsightSummaryDto(
    String kind,
    String title,
    String body,
    List<String> affectedUserNames
) {

}
