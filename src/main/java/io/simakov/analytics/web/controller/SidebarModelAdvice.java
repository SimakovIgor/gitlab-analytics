package io.simakov.analytics.web.controller;

import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.workspace.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "io.simakov.analytics.web.controller")
@RequiredArgsConstructor
public class SidebarModelAdvice {

    private final TeamService teamService;

    @ModelAttribute("teamCount")
    public int teamCount() {
        if (!WorkspaceContext.isSet()) {
            return 0;
        }
        return teamService.countTeams(WorkspaceContext.get());
    }
}
