package io.simakov.analytics.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.RunSnapshotRequest;
import io.simakov.analytics.api.dto.response.RunSnapshotResponse;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ReportMode;
import io.simakov.analytics.domain.model.enums.ScopeType;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    static final int DEFAULT_WINDOW_DAYS = 30;
    private static final ReportMode DEFAULT_REPORT_MODE = ReportMode.MERGED_IN_PERIOD;

    private final MetricCalculationService metricCalculationService;
    private final MetricSnapshotRepository snapshotRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "${app.snapshot.cron:0 0 2 * * *}")
    public void runDailySnapshot() {
        log.info("Running daily metric snapshot");
        run(null, null, DEFAULT_WINDOW_DAYS, DEFAULT_REPORT_MODE, LocalDate.now(ZoneOffset.UTC));
    }

    public RunSnapshotResponse runSnapshot(RunSnapshotRequest request) {
        int windowDays = request != null && request.windowDays() != null
            ? request.windowDays()
            : DEFAULT_WINDOW_DAYS;
        ReportMode reportMode = request != null && request.reportMode() != null
            ? request.reportMode()
            : DEFAULT_REPORT_MODE;
        LocalDate snapshotDate = request != null && request.snapshotDate() != null
            ? request.snapshotDate()
            : LocalDate.now(ZoneOffset.UTC);
        List<Long> userIds = request != null
            ? request.userIds()
            : null;
        List<Long> projectIds = request != null
            ? request.projectIds()
            : null;

        return run(userIds, projectIds, windowDays, reportMode, snapshotDate);
    }

    private RunSnapshotResponse run(List<Long> userIds,
                                    List<Long> projectIds,
                                    int windowDays,
                                    ReportMode reportMode,
                                    LocalDate snapshotDate) {
        List<Long> resolvedUserIds = resolveUserIds(userIds);
        List<Long> resolvedProjectIds = resolveProjectIds(projectIds);

        if (resolvedUserIds.isEmpty() || resolvedProjectIds.isEmpty()) {
            log.warn("No users or projects to snapshot — skipping");
            return new RunSnapshotResponse(0, snapshotDate);
        }

        Instant dateTo = snapshotDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dateFrom = dateTo.minus(windowDays, ChronoUnit.DAYS);

        Map<Long, UserMetrics> metrics = metricCalculationService.calculate(
            resolvedProjectIds, resolvedUserIds, dateFrom, dateTo, reportMode);

        int saved = 0;
        for (Map.Entry<Long, UserMetrics> entry : metrics.entrySet()) {
            if (saveSnapshot(entry.getKey(), snapshotDate, dateFrom, dateTo,
                windowDays, reportMode, entry.getValue())) {
                saved++;
            }
        }

        log.info("Saved {} snapshots for date={}, windowDays={}, mode={}", saved, snapshotDate, windowDays, reportMode);
        return new RunSnapshotResponse(saved, snapshotDate);
    }

    private boolean saveSnapshot(Long userId,
                                 LocalDate snapshotDate,
                                 Instant dateFrom,
                                 Instant dateTo,
                                 int windowDays,
                                 ReportMode reportMode,
                                 UserMetrics userMetrics) {
        try {
            Map<String, Object> allMetrics = new LinkedHashMap<>(userMetrics.toMetricsMap());
            allMetrics.putAll(userMetrics.toNormalizedMap());
            String json = objectMapper.writeValueAsString(allMetrics);

            MetricSnapshot snapshot = snapshotRepository
                .findByTrackedUserIdAndSnapshotDateAndReportMode(userId, snapshotDate, reportMode)
                .orElseGet(MetricSnapshot::new);

            snapshot.setTrackedUserId(userId);
            snapshot.setSnapshotDate(snapshotDate);
            snapshot.setDateFrom(dateFrom);
            snapshot.setDateTo(dateTo);
            snapshot.setWindowDays(windowDays);
            snapshot.setReportMode(reportMode);
            snapshot.setPeriodType(PeriodType.CUSTOM);
            snapshot.setScopeType(ScopeType.USER);
            snapshot.setMetricsJson(json);
            snapshotRepository.save(snapshot);
            return true;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metrics for user {}", userId, e);
            return false;
        }
    }

    private List<Long> resolveUserIds(List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        return trackedUserRepository.findAllByEnabledTrue().stream()
            .map(TrackedUser::getId)
            .toList();
    }

    private List<Long> resolveProjectIds(List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        return trackedProjectRepository.findAllByEnabledTrue().stream()
            .map(TrackedProject::getId)
            .toList();
    }
}
