package io.simakov.analytics.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.web.MetricTrendService;
import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.ReportViewService;
import io.simakov.analytics.web.SettingsViewService;
import io.simakov.analytics.web.dto.MetricChartData;
import io.simakov.analytics.web.dto.MrSummaryDto;
import io.simakov.analytics.web.dto.ReportPageData;
import io.simakov.analytics.web.dto.SettingsPageData;
import io.simakov.analytics.web.dto.SyncHistoryPageData;
import io.simakov.analytics.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final ReportViewService reportViewService;
    private final MetricTrendService metricTrendService;
    private final SettingsViewService settingsViewService;
    private final OAuth2UserResolver userResolver;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    @GetMapping("/")
    public String home() {
        return "redirect:/report";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/report";
    }

    @GetMapping("/report")
    public String report(OAuth2AuthenticationToken authentication,
                         @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                         @RequestParam(required = false) List<Long> projectIds,
                         @RequestParam(defaultValue = "true") boolean showInactive,
                         @RequestParam(required = false) String metric,
                         Model model) {
        ReportPageData data = reportViewService.buildReportPage(period, projectIds, showInactive);
        if (data.onboardingMode()) {
            return "redirect:/onboarding";
        }

        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }
        model.addAttribute("sources", data.sources());
        model.addAttribute("hasProjects", data.hasProjects());
        model.addAttribute("activeJobIds", data.activeJobIds());
        model.addAttribute("enrichmentJobId", data.enrichmentJobId());
        model.addAttribute("releaseJobIds", data.releaseJobIds());
        model.addAttribute("usersWithAliases", data.usersWithAliases());
        model.addAttribute("allProjects", data.allProjects());
        model.addAttribute("selectedProjectIds", data.selectedProjectIds());
        model.addAttribute("selectedPeriod", data.selectedPeriod());
        model.addAttribute("showInactive", data.showInactive());
        model.addAttribute("dateFrom", data.dateFrom());
        model.addAttribute("dateTo", data.dateTo());
        model.addAttribute("metrics", data.metrics());
        model.addAttribute("deltas", data.deltas());
        model.addAttribute("summary", data.summary());
        model.addAttribute("topInsights", data.topInsights());

        MetricChartData historyData = metricTrendService.buildChartData(metric, period, projectIds, showInactive);
        model.addAttribute("chartData", historyData.chartJson());
        model.addAttribute("selectedMetric", historyData.selectedMetric());
        model.addAttribute("metricLabel", historyData.metricLabel());
        model.addAttribute("metricOptions", historyData.metricOptions());
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(WorkspaceContext.get()));

        return "report";
    }

    @GetMapping(value = "/report/chart",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String chartJson(@RequestParam(defaultValue = "LAST_30_DAYS") String period,
                            @RequestParam(required = false) List<Long> projectIds,
                            @RequestParam(defaultValue = "false") boolean showInactive,
                            @RequestParam(required = false) String metric) {
        return metricTrendService.buildChartData(metric, period, projectIds, showInactive).chartJson();
    }

    @GetMapping("/sync")
    public String syncHistory(OAuth2AuthenticationToken authentication,
                              Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }
        SettingsPageData sidebar = settingsViewService.buildSettingsPage();
        model.addAttribute("allProjects", sidebar.projects());
        model.addAttribute("usersWithAliases", sidebar.usersWithAliases());
        model.addAttribute("sources", sidebar.sources());
        model.addAttribute("showInactive", false);

        SyncHistoryPageData data = settingsViewService.buildSyncHistoryPage();
        model.addAttribute("activeJobIds", data.activeJobIds());
        model.addAttribute("enrichmentJobId", data.enrichmentJobId());
        model.addAttribute("releaseJobIds", data.releaseJobIds());
        model.addAttribute("total14d", data.total14d());
        model.addAttribute("ok14d", data.ok14d());
        model.addAttribute("partial14d", data.partial14d());
        model.addAttribute("failed14d", data.failed14d());
        model.addAttribute("avgDurLabel14d", data.avgDurLabel14d());
        model.addAttribute("projects", data.projects());
        try {
            model.addAttribute("jobsJson", objectMapper.writeValueAsString(data.jobs()));
            model.addAttribute("chartBarsJson", objectMapper.writeValueAsString(data.chartBars()));
        } catch (JsonProcessingException e) {
            model.addAttribute("jobsJson", "[]");
            model.addAttribute("chartBarsJson", "[]");
        }
        model.addAttribute("workspaceName", workspaceService.findWorkspaceName(WorkspaceContext.get()));
        return "sync";
    }

    @GetMapping(value = "/report/user/{id}/mrs",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<MrSummaryDto> userMrs(@PathVariable Long id,
                                      @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                                      @RequestParam(required = false) List<Long> projectIds) {
        return reportViewService.getUserMrs(id, period, projectIds);
    }
}
