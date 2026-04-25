package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.dora.model.DoraMetric;
import io.simakov.analytics.dora.model.DoraRating;
import io.simakov.analytics.jira.JiraIncidentSyncService;
import io.simakov.analytics.jira.JiraProperties;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.sync.SyncOrchestrator;
import io.simakov.analytics.web.DoraService;
import io.simakov.analytics.web.SettingsService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class DoraController {

    private final DoraService doraService;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;
    private final SettingsService settingsService;
    private final SyncOrchestrator syncOrchestrator;
    private final JiraIncidentSyncService jiraIncidentSyncService;
    private final JiraProperties jiraProperties;

    @GetMapping("/dora")
    public String dora(@RequestParam(required = false) List<Long> projectIds,
                       @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                       Model model) {
        PeriodType periodType;
        try {
            periodType = PeriodType.valueOf(period);
        } catch (IllegalArgumentException e) {
            periodType = PeriodType.LAST_30_DAYS;
        }
        int days = periodType.toDays();

        SettingsPageData sidebarData = settingsViewService.buildSettingsPage();
        List<TrackedProject> allProjects = sidebarData.projects();
        Set<Long> workspaceIds = allProjects.stream().map(TrackedProject::getId).collect(Collectors.toSet());
        List<Long> effectiveProjectIds = (projectIds != null && !projectIds.isEmpty())
            ? projectIds.stream().filter(workspaceIds::contains).toList()
            : allProjects.stream().map(TrackedProject::getId).toList();

        Map<String, Object> leadTime = doraService.buildLeadTimeData(effectiveProjectIds, days);
        Map<String, Object> deployFreq = doraService.buildDeployFrequencyData(effectiveProjectIds, days);
        Map<String, Object> cfr = doraService.buildChangeFailureRateData(effectiveProjectIds, days);
        Map<String, Object> mttr = doraService.buildMttrData(effectiveProjectIds, days);
        List<DoraService.ReleaseRowDto> releases = doraService.buildReleasesData(effectiveProjectIds);

        model.addAttribute("projects", allProjects);
        model.addAttribute("allProjects", allProjects);
        model.addAttribute("sources", sidebarData.sources());
        model.addAttribute("usersWithAliases", sidebarData.usersWithAliases());
        model.addAttribute("activeJobIds", sidebarData.activeJobIds());
        Long workspaceId = WorkspaceContext.get();
        Long enrichmentJobId = syncJobService.findActiveEnrichmentJob(workspaceId)
            .map(SyncJob::getId).orElse(null);
        model.addAttribute("enrichmentJobId", enrichmentJobId);
        model.addAttribute("releaseJobIds", syncJobService.findActiveReleaseJobIds(workspaceId));
        model.addAttribute("jiraJobIds", syncJobService.findActiveJiraJobIds(workspaceId));
        model.addAttribute("hasProjects", sidebarData.hasProjects());
        model.addAttribute("showInactive", false);
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(workspaceId));
        model.addAttribute("selectedProjectIds", effectiveProjectIds);
        model.addAttribute("selectedPeriod", periodType.name());
        model.addAttribute("dateFrom", java.time.LocalDate.now().minusDays(days));
        model.addAttribute("dateTo", java.time.LocalDate.now());
        List<DoraService.ServiceHealthRow> serviceHealth =
            doraService.buildServiceHealthData(effectiveProjectIds, days);
        model.addAttribute("serviceHealthRows", serviceHealth);
        populateMetricAttributes(model, leadTime, deployFreq, cfr, mttr);
        model.addAttribute("releases", releases);
        model.addAttribute("doraMetrics", DoraMetric.values());
        model.addAttribute("jiraEnabled", true);
        String jiraBase = jiraProperties.baseUrl() != null
            ? jiraProperties.baseUrl().replaceAll("/+$", "")
            : "";
        model.addAttribute("jiraBaseUrl", jiraBase);

        boolean hasReleases = !releases.isEmpty();
        boolean hasJiraConfig = jiraProperties.baseUrl() != null
            && !jiraProperties.baseUrl().isBlank();
        long totalIncidents = ((Number) cfr.getOrDefault("totalIncidents", 0L)).longValue();
        boolean hasIncidents = totalIncidents > 0;
        model.addAttribute("hasReleases", hasReleases);
        model.addAttribute("hasJiraConfig", hasJiraConfig);
        model.addAttribute("hasIncidents", hasIncidents);

        return "dora";
    }

    private void populateMetricAttributes(Model model,
                                          Map<String, Object> leadTime,
                                          Map<String, Object> deployFreq,
                                          Map<String, Object> cfr,
                                          Map<String, Object> mttr) {
        model.addAttribute("totalMrs", leadTime.get("totalMrs"));
        model.addAttribute("medianDays", leadTime.get("medianDays"));
        model.addAttribute("p75Days", leadTime.get("p75Days"));
        model.addAttribute("p95Days", leadTime.get("p95Days"));
        DoraRating ltRating = (DoraRating) leadTime.get("leadTimeRating");
        model.addAttribute("leadTimeRating", ltRating);
        model.addAttribute("leadTimeRatingDesc",
            DoraMetric.LEAD_TIME_FOR_CHANGES.ratingDescription(ltRating));
        model.addAttribute("chartJson", leadTime.get("chartJson"));
        model.addAttribute("totalDeploys", deployFreq.get("totalDeploys"));
        model.addAttribute("deploysPerDay", deployFreq.get("deploysPerDay"));
        model.addAttribute("deployDisplayValue", deployFreq.get("displayValue"));
        model.addAttribute("deployDisplayUnit", deployFreq.get("displayUnit"));
        DoraRating dfRating = (DoraRating) deployFreq.get("deployFreqRating");
        model.addAttribute("deployFreqRating", dfRating);
        model.addAttribute("deployFreqRatingDesc",
            DoraMetric.DEPLOYMENT_FREQUENCY.ratingDescription(dfRating));
        model.addAttribute("deployFreqChartJson", deployFreq.get("chartJson"));
        model.addAttribute("cfrTotalIncidents", cfr.get("totalIncidents"));
        model.addAttribute("cfrTotalDeploys", cfr.get("totalDeploys"));
        model.addAttribute("cfrPercent", cfr.get("cfrPercent"));
        DoraRating cfrRating = cfr.get("cfrRating") instanceof DoraRating r
            ? r
            : DoraRating.NO_DATA;
        model.addAttribute("cfrRating", cfrRating);
        model.addAttribute("cfrRatingDesc",
            DoraMetric.CHANGE_FAILURE_RATE.ratingDescription(cfrRating));
        model.addAttribute("cfrChartJson", cfr.get("chartJson"));
        model.addAttribute("mttrHours", mttr.get("mttrHours"));
        model.addAttribute("mttrTotalIncidents", mttr.get("totalIncidents"));
        DoraRating mttrRating = mttr.get("mttrRating") instanceof DoraRating r
            ? r
            : DoraRating.NO_DATA;
        model.addAttribute("mttrRating", mttrRating);
        model.addAttribute("mttrRatingDesc",
            DoraMetric.MTTR.ratingDescription(mttrRating));
        model.addAttribute("mttrChartJson", mttr.get("chartJson"));
    }

    @PostMapping("/dora/sync/releases")
    @ResponseBody
    public List<Map<String, Object>> triggerReleaseSync(@RequestParam(required = false) Long projectId) {
        List<Long> ids = projectId != null
            ? List.of(projectId)
            : List.of();
        return settingsService.startReleaseSyncForProjectsDetailed(ids);
    }

    @PostMapping("/dora/sync/incidents")
    @ResponseBody
    public Map<String, Object> triggerIncidentSync(
        @RequestParam(defaultValue = "360") int days) {
        Long workspaceId = WorkspaceContext.get();
        if (syncJobService.findActiveJiraIncidentJob(workspaceId).isPresent()) {
            return Map.of("status", "already_running",
                "message", "Jira incident sync is already running");
        }
        SyncJob job = syncJobService.createJiraIncidentJob(workspaceId, null);
        syncOrchestrator.orchestrateJiraIncidentsAsync(job.getId(), days);
        return Map.of("status", "ok", "jobId", job.getId());
    }
}
