package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoraService {

    private final MergeRequestRepository mergeRequestRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final ObjectMapper objectMapper;

    private static double round(Number value) {
        if (value == null) {
            return 0.0;
        }
        return Math.round(value.doubleValue() * 10.0) / 10.0;
    }

    public List<TrackedProject> getAllProjects() {
        return trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(WorkspaceContext.get());
    }

    /**
     * Lead time summary + weekly chart data for given projects and days back.
     * Returns map with keys: totalMrs, medianHours, p75Hours, p95Hours, chartJson.
     */
    public Map<String, Object> buildLeadTimeData(List<Long> projectIds,
                                                 int days) {
        List<Long> resolvedIds = resolveProjectIds(projectIds);
        Instant dateFrom = DateTimeUtils.now().minus(days, ChronoUnit.DAYS);

        List<Object[]> summaryRows = mergeRequestRepository.findLeadTimeSummary(resolvedIds, dateFrom);
        Object[] summary = summaryRows.isEmpty()
            ? null
            : summaryRows.getFirst();
        List<Object[]> weekly = mergeRequestRepository.findLeadTimeByWeek(resolvedIds, dateFrom);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalMrs", summary != null
            ? ((Number) summary[0]).intValue()
            : 0);
        result.put("medianHours", summary != null
            ? round((Number) summary[1])
            : null);
        result.put("p75Hours", summary != null
            ? round((Number) summary[2])
            : null);
        result.put("p95Hours", summary != null
            ? round((Number) summary[3])
            : null);
        result.put("chartJson", buildChartJson(weekly));
        return result;
    }

    private List<Long> resolveProjectIds(List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        return trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(WorkspaceContext.get()).stream()
            .map(TrackedProject::getId)
            .toList();
    }

    private String buildChartJson(List<Object[]> rows) {
        List<String> labels = new ArrayList<>();
        List<Double> medians = new ArrayList<>();
        List<Double> p75s = new ArrayList<>();

        for (Object[] row : rows) {
            labels.add(row[0].toString());
            medians.add(round((Number) row[2]));
            p75s.add(round((Number) row[3]));
        }

        Map<String, Object> chart = Map.of(
            "labels", labels,
            "datasets", List.of(
                Map.of(
                    "label", "Медиана (ч)",
                    "data", medians,
                    "borderColor", "#f59e0b",
                    "backgroundColor", "rgba(245,158,11,0.08)",
                    "fill", true,
                    "tension", 0.3,
                    "pointRadius", 3
                ),
                Map.of(
                    "label", "P75 (ч)",
                    "data", p75s,
                    "borderColor", "#94a3b8",
                    "backgroundColor", "transparent",
                    "borderDash", List.of(4, 4),
                    "tension", 0.3,
                    "pointRadius", 2
                )
            )
        );

        try {
            return objectMapper.writeValueAsString(chart);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize DORA chart data", e);
            return "{}";
        }
    }
}
