package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.insights.InsightService;
import io.simakov.analytics.insights.model.InsightKind;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.web.OAuth2UserResolver;
import io.simakov.analytics.web.dto.InsightsPageData;
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
    private final TrackedProjectRepository trackedProjectRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final OAuth2UserResolver userResolver;

    @GetMapping("/insights")
    public String insights(OAuth2AuthenticationToken authentication,
                           @RequestParam(defaultValue = "LAST_30_DAYS") String period,
                           @RequestParam(required = false) List<Long> projectIds,
                           Model model) {
        Long workspaceId = WorkspaceContext.get();

        if (authentication != null) {
            model.addAttribute("currentUser", userResolver.resolve(authentication));
        }

        List<TrackedProject> allProjects = trackedProjectRepository.findAllByWorkspaceId(workspaceId);
        List<TrackedUser> allUsers = trackedUserRepository.findAllByWorkspaceId(workspaceId);

        List<TeamInsight> insights = insightService.evaluate(workspaceId, period, projectIds);

        Map<Long, String> usersMap = allUsers.stream()
            .collect(Collectors.toMap(TrackedUser::getId, TrackedUser::getDisplayName));

        List<Long> selectedProjectIds = (projectIds != null && !projectIds.isEmpty())
            ? projectIds
            : allProjects.stream().map(TrackedProject::getId).toList();

        int algoCount = insights.size(); // all are algo for now
        Map<InsightKind, Long> kindCounts = insights.stream()
            .collect(Collectors.groupingBy(TeamInsight::kind, Collectors.counting()));

        InsightsPageData data = new InsightsPageData(
            insights, usersMap, allProjects, selectedProjectIds, period, algoCount, kindCounts
        );

        model.addAttribute("data", data);
        model.addAttribute("insights", data.insights());
        model.addAttribute("users", data.users());
        model.addAttribute("allProjects", data.allProjects());
        model.addAttribute("selectedProjectIds", data.selectedProjectIds());
        model.addAttribute("selectedPeriod", data.selectedPeriod());
        model.addAttribute("algoCount", data.algoCount());
        model.addAttribute("kindCounts", data.kindCounts());
        model.addAttribute("totalCount", insights.size());

        return "insights";
    }
}
