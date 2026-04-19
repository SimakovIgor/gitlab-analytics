package io.simakov.analytics.web.controller;

import io.simakov.analytics.web.HistoryViewService;
import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.ReportViewService;
import io.simakov.analytics.web.dto.HistoryPageData;
import io.simakov.analytics.web.dto.MrSummaryDto;
import io.simakov.analytics.web.dto.ReportPageData;
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
    private final HistoryViewService historyViewService;
    private final OAuth2UserResolver userResolver;

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
                         @RequestParam(defaultValue = "mr_merged_count") String metric,
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

        HistoryPageData historyData = historyViewService.buildHistoryPage(metric, period, projectIds, showInactive);
        model.addAttribute("chartData", historyData.chartJson());
        model.addAttribute("selectedMetric", historyData.selectedMetric());
        model.addAttribute("metricLabel", historyData.metricLabel());
        model.addAttribute("metricOptions", historyData.metricOptions());

        return "report";
    }

    @GetMapping(value = "/report/chart",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String chartJson(@RequestParam(defaultValue = "LAST_30_DAYS") String period,
                            @RequestParam(required = false) List<Long> projectIds,
                            @RequestParam(defaultValue = "false") boolean showInactive,
                            @RequestParam(defaultValue = "mr_merged_count") String metric) {
        return historyViewService.buildHistoryPage(metric, period, projectIds, showInactive).chartJson();
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
