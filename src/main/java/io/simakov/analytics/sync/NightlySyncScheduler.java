package io.simakov.analytics.sync;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import io.simakov.analytics.domain.repository.SyncJobRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.WorkspaceRepository;
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

    private final WorkspaceRepository workspaceRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final SyncJobRepository syncJobRepository;
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final AppProperties appProperties;

    @Scheduled(cron = "${app.sync.cron:0 0 3 * * *}")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void runNightlySync() {
        List<Workspace> workspaces = workspaceRepository.findAll();
        if (workspaces.isEmpty()) {
            log.info("Nightly sync skipped — no workspaces");
            return;
        }
        int windowDays = appProperties.sync().windowDays();
        Instant dateTo = DateTimeUtils.now();
        Instant dateFrom = DateTimeUtils.minusDays(dateTo, windowDays);

        for (Workspace workspace : workspaces) {
            try {
                syncForWorkspace(workspace, dateFrom, dateTo);
            } catch (Exception e) {
                log.error("Nightly sync failed for workspace={}: {}", workspace.getId(), e.getMessage(), e);
            }
        }
    }

    @SuppressWarnings("checkstyle:ReturnCount")
    private void syncForWorkspace(Workspace workspace,
                                  Instant dateFrom,
                                  Instant dateTo) {
        Long workspaceId = workspace.getId();

        List<Long> projectIds = trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId)
            .stream().map(TrackedProject::getId).toList();
        if (projectIds.isEmpty()) {
            log.debug("Nightly sync skipped for workspace={} — no enabled projects", workspaceId);
            return;
        }

        boolean alreadyRunning = syncJobRepository.existsByWorkspaceIdAndStatus(workspaceId, SyncStatus.STARTED);
        if (alreadyRunning) {
            log.warn("Nightly sync skipped for workspace={} — sync already running", workspaceId);
            return;
        }

        ManualSyncRequest request = new ManualSyncRequest(
            projectIds, dateFrom, dateTo, true, true, true
        );
        log.info("Starting nightly sync: workspace={}, projects={}", workspaceId, projectIds.size());
        var job = syncJobService.create(workspaceId, request);
        syncOrchestrator.orchestrateAsync(job.getId(), request);
    }
}
