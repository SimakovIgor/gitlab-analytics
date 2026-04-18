package io.simakov.analytics.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.RunSnapshotRequest;
import io.simakov.analytics.api.dto.response.RunSnapshotResponse;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ScopeType;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final MetricCalculationService metricCalculationService;
    private final MetricSnapshotRepository snapshotRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    /**
     * Создаёт еженедельные снапшоты за последние {@code days} дней (шаг 7 дней).
     * Используется при онбординге для немедленного наполнения истории.
     * Возвращает суммарное количество сохранённых снапшотов.
     */
    @Async("syncTaskExecutor")
    public void runWeeklyBackfillAsync(int days) {
        runWeeklyBackfill(days);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public int runWeeklyBackfill(int days) {
        LocalDate today = DateTimeUtils.currentDateUtc();
        int windowDays = appProperties.snapshot().windowDays();
        int total = 0;
        // Weekly snapshots from oldest to most recent (step = 7 days)
        for (int d = days; d > 0; d -= 7) {
            LocalDate snapshotDate = today.minusDays(d);
            RunSnapshotResponse resp = runSnapshot(
                new RunSnapshotRequest(null, null, windowDays, snapshotDate));
            total += resp.snapshotsCreated();
        }
        // Always include today regardless of whether it falls on a week boundary
        RunSnapshotResponse todayResp = runSnapshot(
            new RunSnapshotRequest(null, null, windowDays, today));
        total += todayResp.snapshotsCreated();
        log.info("Weekly backfill completed: {} snapshots saved for last {} days", total, days);
        return total;
    }

    @Scheduled(cron = "${app.snapshot.cron:0 0 2 * * *}")
    public void runDailySnapshot() {
        log.info("Running daily metric snapshot");
        run(null, null, appProperties.snapshot().windowDays(), DateTimeUtils.currentDateUtc());
    }

    public RunSnapshotResponse runSnapshot(RunSnapshotRequest request) {
        int windowDays = Objects.requireNonNullElse(request.windowDays(), appProperties.snapshot().windowDays());
        LocalDate snapshotDate = Objects.requireNonNullElse(request.snapshotDate(), DateTimeUtils.currentDateUtc());
        return run(request.userIds(), request.projectIds(), windowDays, snapshotDate);
    }

    private RunSnapshotResponse run(List<Long> userIds,
                                    List<Long> projectIds,
                                    int windowDays,
                                    LocalDate snapshotDate) {
        List<Long> resolvedUserIds = resolveUserIds(userIds);
        List<Long> resolvedProjectIds = resolveProjectIds(projectIds);

        if (resolvedUserIds.isEmpty() || resolvedProjectIds.isEmpty()) {
            log.warn("No users or projects to snapshot — skipping");
            return new RunSnapshotResponse(0, snapshotDate);
        }

        Instant dateTo = DateTimeUtils.startOfDayUtc(snapshotDate);
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, windowDays);

        Map<Long, UserMetrics> metrics = metricCalculationService.calculate(
            resolvedProjectIds, resolvedUserIds, dateFrom, dateTo);

        int saved = 0;
        for (Map.Entry<Long, UserMetrics> entry : metrics.entrySet()) {
            if (saveSnapshot(entry.getKey(), snapshotDate, dateFrom, dateTo, windowDays, entry.getValue())) {
                saved++;
            }
        }

        log.info("Saved {} snapshots for date={}, windowDays={}", saved, snapshotDate, windowDays);
        return new RunSnapshotResponse(saved, snapshotDate);
    }

    private boolean saveSnapshot(Long userId,
                                 LocalDate snapshotDate,
                                 Instant dateFrom,
                                 Instant dateTo,
                                 int windowDays,
                                 UserMetrics userMetrics) {
        try {
            Map<String, Object> allMetrics = new LinkedHashMap<>(userMetrics.toMetricsMap());
            allMetrics.putAll(userMetrics.toNormalizedMap());
            String json = objectMapper.writeValueAsString(allMetrics);

            MetricSnapshot snapshot = snapshotRepository
                .findByTrackedUserIdAndSnapshotDate(userId, snapshotDate)
                .orElseGet(MetricSnapshot::new);

            snapshot.setTrackedUserId(userId);
            snapshot.setSnapshotDate(snapshotDate);
            snapshot.setDateFrom(dateFrom);
            snapshot.setDateTo(dateTo);
            snapshot.setWindowDays(windowDays);
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
