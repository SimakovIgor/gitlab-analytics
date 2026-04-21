package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.insights.InsightService;
import io.simakov.analytics.insights.model.InsightKind;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
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
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class InsightsController {

    private final InsightService insightService;
    private final OAuth2UserResolver userResolver;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;

    @GetMapping("/insights")
    public String insights(OAuth2AuthenticationToken authentication,
                           @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                           @RequestParam(required = false) List<Long> projectIds,
                           Model model) {
        Long workspaceId = WorkspaceContext.get();

        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

        SettingsPageData sidebarData = settingsViewService.buildSettingsPage();
        List<TrackedProject> allProjects = sidebarData.projects();

        List<TeamInsight> insights = insightService.evaluate(workspaceId, period, projectIds);

        Map<Long, String> usersMap = sidebarData.usersWithAliases().stream()
            .collect(Collectors.toMap(
                u -> ((TrackedUser) u.get("user")).getId(),
                u -> ((TrackedUser) u.get("user")).getDisplayName()
            ));

        List<Long> selectedProjectIds = (projectIds != null && !projectIds.isEmpty())
            ? projectIds
            : allProjects.stream().map(TrackedProject::getId).toList();

        int algoCount = insights.size();
        Map<InsightKind, Long> kindCounts = insights.stream()
            .collect(Collectors.groupingBy(TeamInsight::kind, Collectors.counting()));

        // Sidebar required attributes
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

        // Page-specific attributes
        model.addAttribute("insights", insights);
        model.addAttribute("users", usersMap);
        model.addAttribute("selectedProjectIds", selectedProjectIds);
        model.addAttribute("selectedPeriod", period);
        model.addAttribute("algoCount", algoCount);
        model.addAttribute("kindCounts", kindCounts);
        model.addAttribute("totalCount", insights.size());

        return "insights";
    }
}
