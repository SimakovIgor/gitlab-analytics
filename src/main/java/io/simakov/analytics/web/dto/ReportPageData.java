package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.metrics.model.UserMetrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReportPageData(
    List<GitSource> sources,
    boolean hasSources,
    boolean hasProjects,
    boolean hasUsers,
    boolean onboardingMode,
    boolean hasSyncCompleted,
    List<Long> activeJobIds,
    List<Map<String, Object>> usersWithAliases,
    List<TrackedProject> allProjects,
    List<Long> selectedProjectIds,
    String selectedPeriod,
    boolean showInactive,
    Instant dateFrom,
    Instant dateTo,
    List<UserMetrics> metrics,
    Map<Long, Map<String, Number>> deltas,
    ReportSummary summary
) {

}
