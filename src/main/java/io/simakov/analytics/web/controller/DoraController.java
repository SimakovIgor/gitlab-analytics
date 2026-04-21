package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.web.DoraService;
import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class DoraController {

    private final DoraService doraService;
    private final OAuth2UserResolver userResolver;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;

    @GetMapping("/dora")
    public String dora(OAuth2AuthenticationToken authentication,
                       @RequestParam(required = false) List<Long> projectIds,
                       @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                       Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

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

        model.addAttribute("projects", allProjects);
        model.addAttribute("allProjects", allProjects);
        model.addAttribute("sources", sidebarData.sources());
        model.addAttribute("usersWithAliases", sidebarData.usersWithAliases());
        model.addAttribute("activeJobIds", sidebarData.activeJobIds());
        Long enrichmentJobId = syncJobService.findActiveEnrichmentJob(WorkspaceContext.get())
            .map(SyncJob::getId).orElse(null);
        model.addAttribute("enrichmentJobId", enrichmentJobId);
        model.addAttribute("hasProjects", sidebarData.hasProjects());
        model.addAttribute("showInactive", false);
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(WorkspaceContext.get()));
        model.addAttribute("selectedProjectIds", effectiveProjectIds);
        model.addAttribute("selectedPeriod", periodType.name());
        model.addAttribute("dateFrom", java.time.LocalDate.now().minusDays(days));
        model.addAttribute("dateTo", java.time.LocalDate.now());
        model.addAttribute("totalMrs", leadTime.get("totalMrs"));
        model.addAttribute("medianHours", leadTime.get("medianHours"));
        model.addAttribute("p75Hours", leadTime.get("p75Hours"));
        model.addAttribute("p95Hours", leadTime.get("p95Hours"));
        model.addAttribute("chartJson", leadTime.get("chartJson"));

        return "dora";
    }
}
