package io.simakov.analytics.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.response.SnapshotHistoryResponse;
import io.simakov.analytics.api.dto.response.SnapshotHistoryResponse.SnapshotPoint;
import io.simakov.analytics.api.dto.response.SnapshotHistoryResponse.UserSnapshotMetrics;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.TimeGroupBy;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotHistoryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final MetricSnapshotRepository snapshotRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final ObjectMapper objectMapper;

    public SnapshotHistoryResponse getHistory(Long workspaceId,
                                              List<Long> userIds,
                                              LocalDate from,
                                              LocalDate to,
                                              TimeGroupBy groupBy) {
        List<MetricSnapshot> snapshots = snapshotRepository.findHistoryByWorkspace(workspaceId, userIds, from, to);

        Map<Long, String> userNames = loadUserNames(userIds);

        // Group snapshots by time period label, preserving insertion order
        Map<String, List<MetricSnapshot>> grouped = snapshots.stream()
            .collect(Collectors.groupingBy(
                s -> periodLabel(s.getSnapshotDate(), groupBy),
                LinkedHashMap::new,
                Collectors.toList()));

        List<SnapshotPoint> points = new ArrayList<>();
        for (Map.Entry<String, List<MetricSnapshot>> entry : grouped.entrySet()) {
            points.add(buildPoint(entry.getKey(), entry.getValue(), userNames));
        }

        return new SnapshotHistoryResponse(groupBy.name(), points);
    }

    private SnapshotPoint buildPoint(String label,
                                     List<MetricSnapshot> group,
                                     Map<Long, String> userNames) {
        // For each user in the group, take the snapshot with the latest snapshotDate
        Map<Long, MetricSnapshot> latestPerUser = group.stream()
            .collect(Collectors.toMap(
                MetricSnapshot::getTrackedUserId,
                s -> s,
                (a, b) -> a.getSnapshotDate().isAfter(b.getSnapshotDate())
                    ? a
                    : b));

        List<UserSnapshotMetrics> users = latestPerUser.values().stream()
            .map(metricSnapshot -> toUserMetrics(metricSnapshot, userNames))
            .toList();

        return new SnapshotPoint(label, users);
    }

    private UserSnapshotMetrics toUserMetrics(MetricSnapshot snap,
                                              Map<Long, String> userNames) {
        return new UserSnapshotMetrics(
            snap.getTrackedUserId(),
            userNames.getOrDefault(snap.getTrackedUserId(), "Unknown"),
            snap.getSnapshotDate(),
            parseMetrics(snap.getMetricsJson()));
    }

    private String periodLabel(LocalDate date,
                               TimeGroupBy groupBy) {
        return switch (groupBy) {
            case DAY -> date.toString();
            case WEEK -> date.get(IsoFields.WEEK_BASED_YEAR)
                + "-W" + String.format("%02d", date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        };
    }

    private Map<Long, String> loadUserNames(List<Long> userIds) {
        return trackedUserRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(TrackedUser::getId, TrackedUser::getDisplayName));
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private Map<String, Object> parseMetrics(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            log.error("Failed to parse metrics JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}
