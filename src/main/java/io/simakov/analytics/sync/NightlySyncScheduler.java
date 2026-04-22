package io.simakov.analytics.sync;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.domain.model.enums.SyncJobPhase;
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
    private final SyncJobService syncJobService;
    private final SyncOrchestrator syncOrchestrator;
    private final ReleaseSyncService releaseSyncService;
    private final AppProperties appProperties;

    @Scheduled(cron = "${app.sync.cron:0 0 3 * * *}")
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

            try {
                releaseSyncService.syncReleasesForWorkspace(workspace.getId());
            } catch (Exception e) {
                log.error("Release sync failed for workspace={}: {}", workspace.getId(), e.getMessage(), e);
            }
        }
    }

    private void syncForWorkspace(Workspace workspace,
                                  Instant dateFrom,
                                  Instant dateTo) {
        Long workspaceId = workspace.getId();
        List<TrackedProject> projects = trackedProjectRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId);

        if (projects.isEmpty()) {
            log.debug("Nightly sync skipped for workspace={} — no enabled projects", workspaceId);
            return;
        }

        // One ENRICH job per project: independent progress tracking, per-project retry on failure.
        int launched = 0;
        for (TrackedProject project : projects) {
            List<Long> projectId = List.of(project.getId());
            if (syncJobService.findActiveJobForProjects(workspaceId, projectId).isPresent()) {
                log.warn("Nightly sync skipped for project='{}' workspace={} — sync already running",
                    project.getName(), workspaceId);
                continue;
            }
            ManualSyncRequest request = new ManualSyncRequest(
                projectId, dateFrom, dateTo, true, true, true, true
            );
            var job = syncJobService.create(workspaceId, request, SyncJobPhase.ENRICH);
            syncOrchestrator.orchestrateAsync(job.getId(), request, SyncJobPhase.ENRICH);
            launched++;
        }
        log.info("Nightly sync: workspace={}, launched {}/{} project job(s)",
            workspaceId, launched, projects.size());
    }
}
