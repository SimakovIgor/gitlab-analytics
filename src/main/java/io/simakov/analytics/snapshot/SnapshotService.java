package io.simakov.analytics.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.api.dto.request.RunSnapshotRequest;
import io.simakov.analytics.api.dto.response.RunSnapshotResponse;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ScopeType;
import io.simakov.analytics.domain.repository.MetricSnapshotRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
import io.simakov.analytics.metrics.MetricCalculationService;
import io.simakov.analytics.metrics.model.UserMetrics;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final MetricCalculationService metricCalculationService;
    private final MetricSnapshotRepository snapshotRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    /**
     * Создаёт ежедневные снапшоты за последние {@code days} дней (шаг 1 день).
     * Использует текущий WorkspaceContext.
     */
    @Async("syncTaskExecutor")
    public void runDailyBackfillAsync(Long workspaceId, int days) {
        runDailyBackfill(workspaceId, days);
    }

    public int runDailyBackfill(int days) {
        return runDailyBackfill(WorkspaceContext.get(), days);
    }

    @SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "checkstyle:IllegalCatch"})
    public int runDailyBackfill(Long workspaceId,
                                int days) {
        LocalDate today = DateTimeUtils.currentDateUtc();
        int windowDays = appProperties.snapshot().windowDays();
        int total = 0;
        for (int d = days; d >= 0; d--) {
            LocalDate snapshotDate = today.minusDays(d);
            try {
                RunSnapshotResponse resp = runSnapshotForWorkspace(
                    workspaceId, new RunSnapshotRequest(null, null, windowDays, snapshotDate));
                total += resp.snapshotsCreated();
            } catch (Exception e) {
                log.error("Backfill snapshot failed for workspace={}, date={}: {}", workspaceId, snapshotDate, e.getMessage(), e);
            }
        }
        log.info("Daily backfill completed: workspace={}, {} snapshots for last {} days", workspaceId, total, days);
        return total;
    }

    @Scheduled(cron = "${app.snapshot.cron:0 0 2 * * *}")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void runDailySnapshot() {
        log.info("Running daily metric snapshot for all workspaces");
        List<Workspace> workspaces = workspaceRepository.findAll();
        int windowDays = appProperties.snapshot().windowDays();
        LocalDate today = DateTimeUtils.currentDateUtc();
        for (Workspace workspace : workspaces) {
            try {
                run(workspace.getId(), null, null, windowDays, today);
            } catch (Exception e) {
                log.error("Daily snapshot failed for workspace={}: {}", workspace.getId(), e.getMessage(), e);
            }
        }
    }

    public RunSnapshotResponse runSnapshot(RunSnapshotRequest request) {
        return runSnapshotForWorkspace(WorkspaceContext.get(), request);
    }

    public RunSnapshotResponse runSnapshotForWorkspace(Long workspaceId,
                                                       RunSnapshotRequest request) {
        int windowDays = Objects.requireNonNullElse(request.windowDays(), appProperties.snapshot().windowDays());
        LocalDate snapshotDate = Objects.requireNonNullElse(request.snapshotDate(), DateTimeUtils.currentDateUtc());
        return run(workspaceId, request.userIds(), request.projectIds(), windowDays, snapshotDate);
    }

    private RunSnapshotResponse run(Long workspaceId,
                                    List<Long> userIds,
                                    List<Long> projectIds,
                                    int windowDays,
                                    LocalDate snapshotDate) {
        List<Long> resolvedUserIds = resolveUserIds(workspaceId, userIds);
        List<Long> resolvedProjectIds = resolveProjectIds(workspaceId, projectIds);

        if (resolvedUserIds.isEmpty() || resolvedProjectIds.isEmpty()) {
            log.warn("No users or projects to snapshot for workspace={} — skipping", workspaceId);
            return new RunSnapshotResponse(0, snapshotDate);
        }

        Instant dateTo = DateTimeUtils.startOfDayUtc(snapshotDate);
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, windowDays);

        Map<Long, UserMetrics> metrics = metricCalculationService.calculate(
            resolvedProjectIds, resolvedUserIds, dateFrom, dateTo);

        Map<Long, MetricSnapshot> existingByUserId = snapshotRepository
            .findByWorkspaceIdAndSnapshotDateAndTrackedUserIdIn(workspaceId, snapshotDate, resolvedUserIds)
            .stream().collect(Collectors.toMap(MetricSnapshot::getTrackedUserId, s -> s));

        List<MetricSnapshot> toSave = new ArrayList<>();
        for (Map.Entry<Long, UserMetrics> entry : metrics.entrySet()) {
            MetricSnapshot built = buildSnapshot(workspaceId, entry.getKey(), snapshotDate,
                dateFrom, dateTo, windowDays, entry.getValue(),
                existingByUserId.get(entry.getKey()));
            if (built != null) {
                toSave.add(built);
            }
        }
        snapshotRepository.saveAll(toSave);

        log.info("Saved {} snapshots for workspace={}, date={}, windowDays={}", toSave.size(), workspaceId, snapshotDate, windowDays);
        return new RunSnapshotResponse(toSave.size(), snapshotDate);
    }

    private MetricSnapshot buildSnapshot(Long workspaceId,
                                         Long userId,
                                         LocalDate snapshotDate,
                                         Instant dateFrom,
                                         Instant dateTo,
                                         int windowDays,
                                         UserMetrics userMetrics,
                                         MetricSnapshot existing) {
        try {
            Map<String, Object> allMetrics = new LinkedHashMap<>(userMetrics.toMetricsMap());
            allMetrics.putAll(userMetrics.toNormalizedMap());
            String json = objectMapper.writeValueAsString(allMetrics);

            MetricSnapshot snapshot = existing != null ? existing : new MetricSnapshot();
            snapshot.setWorkspaceId(workspaceId);
            snapshot.setTrackedUserId(userId);
            snapshot.setSnapshotDate(snapshotDate);
            snapshot.setDateFrom(dateFrom);
            snapshot.setDateTo(dateTo);
            snapshot.setWindowDays(windowDays);
            snapshot.setPeriodType(PeriodType.CUSTOM);
            snapshot.setScopeType(ScopeType.USER);
            snapshot.setMetricsJson(json);
            return snapshot;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metrics for user {}", userId, e);
            return null;
        }
    }

    private List<Long> resolveUserIds(Long workspaceId,
                                      List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        return trackedUserRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId).stream()
            .map(TrackedUser::getId)
            .toList();
    }

    private List<Long> resolveProjectIds(Long workspaceId,
                                         List<Long> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        return trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId).stream()
            .map(TrackedProject::getId)
            .toList();
    }
}
