package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.insights.InsightService;
import io.simakov.analytics.insights.ai.AiInsightDto;
import io.simakov.analytics.insights.ai.AiInsightService;
import io.simakov.analytics.insights.ai.AnthropicProperties;
import io.simakov.analytics.insights.model.InsightKind;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.workspace.WorkspacePermissionService;
import io.simakov.analytics.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class InsightsController {

    private final InsightService insightService;
    private final AiInsightService aiInsightService;
    private final AnthropicProperties anthropicProperties;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;
    private final WorkspacePermissionService permissionService;

    @GetMapping("/insights")
    public String insights(@RequestParam(defaultValue = "LAST_30_DAYS") String period,
                           @RequestParam(required = false) List<Long> projectIds,
                           Model model) {
        Long workspaceId = WorkspaceContext.get();

        SettingsPageData sidebarData = settingsViewService.buildSettingsPage();
        List<TrackedProject> allProjects = sidebarData.projects();

        List<TeamInsight> insights = insightService.evaluate(workspaceId, period, projectIds);

        // AI insights — served from cache only on page load (no blocking API call)
        List<AiInsightDto> aiInsights = aiInsightService.getCached(workspaceId, period, projectIds);
        Instant aiGeneratedAt = aiInsightService.getCachedGeneratedAt(workspaceId, period, projectIds);

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
        model.addAttribute("releaseJobIds", syncJobService.findActiveReleaseJobIds(workspaceId));
        model.addAttribute("jiraJobIds", syncJobService.findActiveJiraJobIds(workspaceId));

        // Page-specific attributes
        model.addAttribute("insights", insights);
        model.addAttribute("users", usersMap);
        model.addAttribute("selectedProjectIds", selectedProjectIds);
        model.addAttribute("selectedPeriod", period);
        model.addAttribute("algoCount", algoCount);
        model.addAttribute("kindCounts", kindCounts);
        model.addAttribute("totalCount", insights.size() + aiInsights.size());

        // AI insights attributes
        model.addAttribute("aiEnabled", anthropicProperties.isEnabled());
        model.addAttribute("aiInsights", aiInsights);
        model.addAttribute("aiCount", aiInsights.size());
        model.addAttribute("aiGeneratedAt", aiGeneratedAt);

        return "insights";
    }

    /**
     * Owner-only endpoint: invalidates the AI cache and regenerates immediately.
     * Returns JSON {@code {count: N}} on success, {@code {error: "..."}} on failure.
     */
    @PostMapping("/insights/ai/refresh")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> refreshAiInsights(
        @RequestParam(defaultValue = "LAST_30_DAYS") String period,
        @RequestParam(required = false) List<Long> projectIds
    ) {
        permissionService.requireOwner();
        Long workspaceId = WorkspaceContext.get();

        List<TeamInsight> algoInsights = insightService.evaluate(workspaceId, period, projectIds);
        List<AiInsightDto> aiInsights = aiInsightService.refresh(workspaceId, period, projectIds, algoInsights);
        return ResponseEntity.ok(Map.of("count", aiInsights.size()));
    }
}
