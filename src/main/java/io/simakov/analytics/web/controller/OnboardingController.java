package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.Workspace;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
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

    @GetMapping("/onboarding")
    public String showOnboarding(Model model,
                                 @AuthenticationPrincipal AppUserPrincipal principal) {
        model.addAttribute("login", principal.getAppUser().getGithubLogin());
        return "onboarding";
    }

    @PostMapping("/onboarding")
    public String createWorkspace(@RequestParam String workspaceName,
                                  @AuthenticationPrincipal AppUserPrincipal principal,
                                  HttpSession session) {
        Long appUserId = principal.getAppUser().getId();
        Workspace workspace = workspaceService.createWorkspace(workspaceName.trim(), appUserId);
        session.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, workspace.getId());
        return "redirect:/settings";
    }
}
