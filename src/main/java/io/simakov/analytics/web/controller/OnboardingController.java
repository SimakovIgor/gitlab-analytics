package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.web.ReportViewService;
import io.simakov.analytics.web.dto.ReportPageData;
import io.simakov.analytics.workspace.WorkspaceService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class OnboardingController {

    private final WorkspaceService workspaceService;
    private final ReportViewService reportViewService;

    @GetMapping("/onboarding")
    public String showOnboarding(Model model,
                                 @AuthenticationPrincipal AppUserPrincipal principal) {
        if (!WorkspaceContext.isSet()) {
            model.addAttribute("workspaceReady", false);
            model.addAttribute("login", principal.getAppUser().getGithubLogin());
            return "onboarding";
        }

        ReportPageData data = reportViewService.buildReportPage("LAST_30_DAYS", null, true);
        if (!data.onboardingMode()) {
            return "redirect:/report";
        }
        model.addAttribute("workspaceReady", true);
        model.addAttribute("hasSources", data.hasSources());
        model.addAttribute("hasProjects", data.hasProjects());
        model.addAttribute("hasUsers", data.hasUsers());
        model.addAttribute("hasSyncCompleted", data.hasSyncCompleted());
        model.addAttribute("activeJobIds", data.activeJobIds());
        model.addAttribute("sources", data.sources());
        return "onboarding";
    }

    @PostMapping("/onboarding")
    public String createWorkspace(@RequestParam String workspaceName,
                                  @AuthenticationPrincipal AppUserPrincipal principal,
                                  HttpSession session) {
        Long appUserId = principal.getAppUser().getId();
        Workspace workspace = workspaceService.createWorkspace(workspaceName.trim(), appUserId);
        session.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, workspace.getId());
        return "redirect:/onboarding";
    }
}
