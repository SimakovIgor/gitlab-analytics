package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.web.CompareService;
import io.simakov.analytics.web.DoraService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class CompareController {

    private final CompareService compareService;
    private final DoraService doraService;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;

    @GetMapping("/compare")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String compare(@RequestParam(defaultValue = "LAST_30_DAYS") String period,
                          @RequestParam(required = false) List<Long> projectIds,
                          Model model) {
        Long workspaceId = WorkspaceContext.get();

        PeriodType periodType;
        try {
            periodType = PeriodType.valueOf(period);
        } catch (IllegalArgumentException e) {
            periodType = PeriodType.LAST_30_DAYS;
        }
        int days = periodType.toDays();
        Instant dateTo = Instant.now();
        Instant dateFrom = dateTo.minus(days, ChronoUnit.DAYS);

        SettingsPageData sidebarData = settingsViewService.buildSettingsPage();
        List<TrackedProject> allProjects = sidebarData.projects();
        Set<Long> workspaceProjectIds = allProjects.stream()
            .map(TrackedProject::getId).collect(Collectors.toSet());
        List<Long> effectiveProjectIds = (projectIds != null && !projectIds.isEmpty())
            ? projectIds.stream().filter(workspaceProjectIds::contains).toList()
            : allProjects.stream().map(TrackedProject::getId).toList();

        // Sidebar
        model.addAttribute("allProjects", allProjects);
        model.addAttribute("sources", sidebarData.sources());
        model.addAttribute("usersWithAliases", sidebarData.usersWithAliases());
        model.addAttribute("activeJobIds", sidebarData.activeJobIds());
        model.addAttribute("hasProjects", sidebarData.hasProjects());
        model.addAttribute("showInactive", false);
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(workspaceId));
        Long enrichmentJobId = syncJobService.findActiveEnrichmentJob(workspaceId)
            .map(SyncJob::getId).orElse(null);
        model.addAttribute("enrichmentJobId", enrichmentJobId);
        model.addAttribute("releaseJobIds", syncJobService.findActiveReleaseJobIds(workspaceId));
        model.addAttribute("jiraJobIds", syncJobService.findActiveJiraJobIds(workspaceId));

        // Page filters
        model.addAttribute("selectedPeriod", periodType.name());
        model.addAttribute("selectedProjectIds", effectiveProjectIds);

        // Compare data
        List<CompareService.TeamCardData> cards = compareService.buildTeamCards(
            workspaceId, effectiveProjectIds, dateFrom, dateTo);
        CompareService.OrgBenchmark benchmark = compareService.buildBenchmark(cards);
        String trendJson = compareService.buildTrendChartJson(workspaceId, cards, effectiveProjectIds, days);
        Double mttrHours = doraService.computeMttrHours(effectiveProjectIds, dateFrom);

        model.addAttribute("cards", cards);
        model.addAttribute("benchmark", benchmark);
        model.addAttribute("trendJson", trendJson);
        model.addAttribute("mttrHours", mttrHours);
        model.addAttribute("hasTeams", !cards.isEmpty());

        return "compare";
    }
}
