package io.simakov.analytics.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.model.Metric;
import io.simakov.analytics.util.DateTimeUtils;
import io.simakov.analytics.web.dto.HistoryPageData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryViewService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<String> PALETTE = List.of(
        "#FC6D26", "#6554C0", "#00B8D9", "#36B37E",
        "#FF5630", "#FFAB00", "#0065FF", "#00875A"
    );
    private static final Map<String, String> METRIC_OPTIONS = Metric.chartOptions();
    private static final Set<String> MINUTES_METRICS = Metric.minuteKeys();

    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserAliasRepository trackedUserAliasRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final MetricSnapshotRepository metricSnapshotRepository;
    private final ObjectMapper objectMapper;

    public HistoryPageData buildHistoryPage(String metric,
                                            String period,
                                            List<Long> requestedProjectIds,
                                            boolean showInactive) {
        PeriodType periodType;
        try {
            periodType = PeriodType.valueOf(period);
        } catch (IllegalArgumentException e) {
            periodType = PeriodType.LAST_360_DAYS;
        }

        List<TrackedProject> allProjects = trackedProjectRepository.findAll();
        List<Long> selectedProjectIds = (requestedProjectIds == null || requestedProjectIds.isEmpty())
            ? List.of()
            : requestedProjectIds;

        List<TrackedUser> users = trackedUserRepository.findAllByEnabledTrue();

        if (!selectedProjectIds.isEmpty()) {
            List<Long> gitlabUserIds = mergeRequestRepository
                .findDistinctAuthorIdsByTrackedProjectIdIn(selectedProjectIds);
            Set<Long> trackedUserIds = trackedUserAliasRepository
                .findByGitlabUserIdIn(gitlabUserIds)
                .stream().map(TrackedUserAlias::getTrackedUserId)
                .collect(Collectors.toSet());
            users = users.stream().filter(u -> trackedUserIds.contains(u.getId())).toList();
        }

        LocalDate dateTo = DateTimeUtils.currentDateUtc();
        LocalDate dateFrom = dateTo.minusDays(periodType.toDays());

        String chartJson = "{}";
        if (!users.isEmpty()) {
            List<Long> userIds = users.stream().map(TrackedUser::getId).toList();
            List<MetricSnapshot> snapshots = metricSnapshotRepository.findHistory(userIds, dateFrom, dateTo);
            chartJson = buildChartJson(snapshots, users, metric, showInactive);
        }

        return new HistoryPageData(
            chartJson,
            metric,
            periodType.name(),
            METRIC_OPTIONS.getOrDefault(metric, metric),
            METRIC_OPTIONS,
            dateFrom,
            dateTo,
            allProjects,
            selectedProjectIds,
            showInactive
        );
    }

    /**
     * Собирает JSON для Chart.js:
     * labels = отсортированные даты снапшотов,
     * datasets = по одному на каждого пользователя.
     */
    @SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "PMD.CyclomaticComplexity"})
    private String buildChartJson(List<MetricSnapshot> snapshots,
                                  List<TrackedUser> users,
                                  String metric,
                                  boolean showInactive) {
        // userId → (date → value)
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (TrackedUser u : users) {
            byUser.put(u.getId(), new TreeMap<>());
        }

        for (MetricSnapshot snap : snapshots) {
            Map<String, Object> dateValues = byUser.get(snap.getTrackedUserId());
            if (dateValues == null) {
                continue;
            }
            try {
                Map<String, Object> metrics = objectMapper.readValue(snap.getMetricsJson(), MAP_TYPE);
                Object value = metrics.get(metric);
                if (MINUTES_METRICS.contains(metric) && value instanceof Number n) {
                    value = Math.round(n.doubleValue() / 60.0 * 10.0) / 10.0;
                }
                dateValues.put(snap.getSnapshotDate().toString(), value);
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse metrics_json for snapshot id={}", snap.getId());
            }
        }

        // Скрываем пользователей без данных за период, если toggle выключен
        List<TrackedUser> visibleUsers = showInactive
            ? users
            : users.stream()
              .filter(u -> byUser.getOrDefault(u.getId(), Map.of())
                           .values().stream().anyMatch(Objects::nonNull))
              .toList();

        // Все уникальные даты (отсортированы через TreeMap)
        List<String> labels = visibleUsers.stream()
            .flatMap(u -> byUser.getOrDefault(u.getId(), Map.of()).keySet().stream())
            .distinct()
            .sorted()
            .toList();

        List<Map<String, Object>> datasets = new ArrayList<>();
        int colorIdx = 0;
        for (TrackedUser user : visibleUsers) {
            Map<String, Object> dateValues = byUser.get(user.getId());
            List<Object> data = labels.stream()
                .map(date -> dateValues.getOrDefault(date, null))
                .toList();
            String color = PALETTE.get(colorIdx % PALETTE.size());
            colorIdx++;
            datasets.add(buildDataset(user.getDisplayName(), data, color));
        }

        Map<String, Object> chartData = new LinkedHashMap<>();
        chartData.put("labels", labels);
        chartData.put("datasets", datasets);

        try {
            return objectMapper.writeValueAsString(chartData);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize chart data", e);
            return "{}";
        }
    }

    private Map<String, Object> buildDataset(String label, List<Object> data, String color) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", label);
        dataset.put("data", data);
        dataset.put("borderColor", color);
        dataset.put("backgroundColor", color + "22");
        dataset.put("tension", 0.3);
        dataset.put("spanGaps", true);
        dataset.put("borderWidth", 1.5);
        dataset.put("pointRadius", 2);
        dataset.put("pointHoverRadius", 5);
        return dataset;
    }
}
