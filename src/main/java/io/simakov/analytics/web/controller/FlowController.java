package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.sync.SyncJobService;
import io.simakov.analytics.web.FlowService;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class FlowController {

    private final FlowService flowService;
    private final SettingsViewService settingsViewService;
    private final WorkspaceService workspaceService;
    private final SyncJobService syncJobService;
    private final ObjectMapper objectMapper;

    @GetMapping("/flow")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String flow(@RequestParam(defaultValue = "LAST_30_DAYS") String period,
                       @RequestParam(required = false) List<Long> projectIds,
                       @RequestParam(defaultValue = "24") int stuckHours,
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

        // Sidebar attributes
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
        model.addAttribute("selectedProjectIds", effectiveProjectIds);
        model.addAttribute("selectedPeriod", periodType.name());
        model.addAttribute("stuckHours", stuckHours);

        // Flow data
        FlowService.FlowStagesResult flowResult = flowService.buildFlowStages(
            effectiveProjectIds, dateFrom, dateTo);
        List<Map<String, Object>> stages = flowResult.stages();
        model.addAttribute("stages", stages);
        model.addAttribute("mrCount", flowResult.mrCount());
        try {
            model.addAttribute("stagesJson", objectMapper.writeValueAsString(stages));
        } catch (JsonProcessingException e) {
            model.addAttribute("stagesJson", "[]");
        }

        double totalHours = stages.stream()
            .mapToDouble(s -> ((Number) s.get("hours")).doubleValue())
            .sum();
        model.addAttribute("totalHours", Math.round(totalHours * 10.0) / 10.0);

        List<FlowService.StuckMrRow> stuckMrs = flowService.buildStuckMrs(
            effectiveProjectIds, stuckHours);
        model.addAttribute("stuckMrs", stuckMrs);
        model.addAttribute("stuckCount", stuckMrs.size());

        List<FlowService.ReviewBalanceRow> reviewBalance = flowService.buildReviewBalance(
            effectiveProjectIds, dateFrom, dateTo);
        model.addAttribute("reviewBalance", reviewBalance);
        int avgReviews = reviewBalance.isEmpty() ? 0
            : (int) Math.round(reviewBalance.stream()
                .mapToInt(FlowService.ReviewBalanceRow::reviews).average().orElse(0));
        model.addAttribute("avgReviews", avgReviews);

        Map<String, Object> matrixData = flowService.buildReviewMatrix(
            effectiveProjectIds, dateFrom, dateTo);
        try {
            model.addAttribute("matrixJson", objectMapper.writeValueAsString(matrixData));
        } catch (JsonProcessingException e) {
            model.addAttribute("matrixJson", "{\"devNames\":[],\"matrix\":[]}");
        }

        return "flow";
    }
}
