package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;

import java.util.List;
import java.util.Map;

public record SettingsPageData(
    List<GitSource> sources,
    List<TrackedProject> projects,
    Map<Long, String> sourceNames,
    List<Map<String, Object>> usersWithAliases,
    boolean hasSources,
    boolean hasProjects,
    boolean hasUsers,
    boolean onboardingMode,
    List<Long> activeJobIds,
    List<Map<String, Object>> recentJobs
) {

}
