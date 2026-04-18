package io.simakov.analytics.web.controller;

import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.ReportViewService;
import io.simakov.analytics.web.dto.ReportPageData;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final ReportViewService reportViewService;
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
                         @RequestParam(defaultValue = "false") boolean showInactive,
                         Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

        ReportPageData data = reportViewService.buildReportPage(period, projectIds, showInactive);
        model.addAttribute("sources", data.sources());
        model.addAttribute("hasSources", data.hasSources());
        model.addAttribute("hasProjects", data.hasProjects());
        model.addAttribute("hasUsers", data.hasUsers());
        model.addAttribute("onboardingMode", data.onboardingMode());
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

        return "report";
    }
}
