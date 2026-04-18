package io.simakov.analytics.sync;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NightlySyncScheduler {

    private final TrackedProjectRepository trackedProjectRepository;
    private final SyncJobRepository syncJobRepository;
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final AppProperties appProperties;

    @Scheduled(cron = "${app.sync.cron:0 0 3 * * *}")
    @SuppressWarnings("checkstyle:ReturnCount")
    public void runNightlySync() {
        List<Long> projectIds = trackedProjectRepository.findAllByEnabledTrue()
            .stream()
            .map(TrackedProject::getId)
            .toList();

        if (projectIds.isEmpty()) {
            log.info("Nightly sync skipped — no enabled projects");
            return;
        }

        boolean alreadyRunning = !syncJobRepository
            .findByStatusOrderByStartedAtDesc(SyncStatus.STARTED)
            .isEmpty();

        if (alreadyRunning) {
            log.warn("Nightly sync skipped — a sync job is already running");
            return;
        }

        int windowDays = appProperties.sync().windowDays();
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, windowDays);

        ManualSyncRequest request = new ManualSyncRequest(
            projectIds, dateFrom, dateTo,
            true, true, true
        );

        log.info("Starting nightly sync: projects={}, dateFrom={}, dateTo={}", projectIds, dateFrom, dateTo);
        var job = syncJobService.create(request);
        syncOrchestrator.orchestrateAsync(job.getId(), request);
    }
}
