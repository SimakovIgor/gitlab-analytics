package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.insights.model.InsightKind;
import io.simakov.analytics.insights.model.TeamInsight;

import java.util.List;
import java.util.Map;

/**
 * All data needed to render the Insights page.
 *
 * @param insights           all triggered insights, sorted by severity descending
 * @param users              tracked users for the "who" section (id → displayName)
 * @param allProjects        all projects in the workspace (for the project filter)
 * @param selectedProjectIds currently selected project filter
 * @param selectedPeriod     current period string (e.g. "LAST_30_DAYS")
 * @param algoCount          number of algorithmic insights (source = algo)
 * @param kindCounts         map of InsightKind → count, for filter badges
 */
public record InsightsPageData(
    List<TeamInsight> insights,
    Map<Long, String> users,
    List<TrackedProject> allProjects,
    List<Long> selectedProjectIds,
    String selectedPeriod,
    int algoCount,
    Map<InsightKind, Long> kindCounts
) {

}
