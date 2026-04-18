package io.simakov.analytics.web.controller;

import io.simakov.analytics.web.HistoryViewService;
import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.dto.HistoryPageData;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryViewService historyViewService;
    private final OAuth2UserResolver userResolver;

    @GetMapping("/history")
    public String history(OAuth2AuthenticationToken authentication,
                          @RequestParam(defaultValue = "mr_merged_count") String metric,
                          @RequestParam(defaultValue = "360") int days,
                          Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

        HistoryPageData data = historyViewService.buildHistoryPage(metric, days);
        model.addAttribute("chartData", data.chartJson());
        model.addAttribute("selectedMetric", data.selectedMetric());
        model.addAttribute("selectedDays", data.selectedDays());
        model.addAttribute("metricLabel", data.metricLabel());
        model.addAttribute("metricOptions", data.metricOptions());

        return "history";
    }
}
