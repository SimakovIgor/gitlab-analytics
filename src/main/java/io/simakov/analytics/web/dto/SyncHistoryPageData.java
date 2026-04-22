package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.TrackedProject;

import java.util.List;
import java.util.Map;

public record SyncHistoryPageData(
    List<Map<String, Object>> jobs,
    List<Map<String, Object>> chartBars,
    long total14d,
    long ok14d,
    long partial14d,
    long failed14d,
    String avgDurLabel14d,
    List<TrackedProject> projects,
    List<Long> activeJobIds,
    Long enrichmentJobId,
    List<Long> releaseJobIds
) {

}
