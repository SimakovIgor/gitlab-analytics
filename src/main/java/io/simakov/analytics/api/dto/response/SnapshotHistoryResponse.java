package io.simakov.analytics.api.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SnapshotHistoryResponse(
    String groupBy,
    List<SnapshotPoint> points
) {

    public record SnapshotPoint(
        String periodLabel,
        List<UserSnapshotMetrics> users
    ) {

    }

    public record UserSnapshotMetrics(
        Long userId,
        String displayName,
        LocalDate snapshotDate,
        Map<String, Object> metrics
    ) {

    }
}
