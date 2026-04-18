package io.simakov.analytics.web.controller;

import io.simakov.analytics.web.DoraService;
import io.simakov.analytics.web.OAuth2UserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class DoraController {

    private final DoraService doraService;
    private final OAuth2UserResolver userResolver;

    @GetMapping("/dora")
    public String dora(OAuth2AuthenticationToken authentication,
                       @RequestParam(required = false) List<Long> projectIds,
                       @RequestParam(defaultValue = "180") int days,
                       Model model) {
        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

        Map<String, Object> leadTime = doraService.buildLeadTimeData(projectIds, days);

        model.addAttribute("projects", doraService.getAllProjects());
        model.addAttribute("selectedProjectIds", projectIds != null ? projectIds : List.of());
        model.addAttribute("selectedDays", days);
        model.addAttribute("totalMrs", leadTime.get("totalMrs"));
        model.addAttribute("medianHours", leadTime.get("medianHours"));
        model.addAttribute("p75Hours", leadTime.get("p75Hours"));
        model.addAttribute("p95Hours", leadTime.get("p95Hours"));
        model.addAttribute("chartJson", leadTime.get("chartJson"));

        return "dora";
    }
}
