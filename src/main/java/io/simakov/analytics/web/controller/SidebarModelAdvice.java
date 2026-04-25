package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceContext;
import io.simakov.analytics.workspace.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Map;

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

    /**
     * Provides the current user's display info (name + avatarUrl) to all web templates.
     * Replaces per-controller OAuth2AuthenticationToken resolution — works for both
     * GitHub OAuth and email+password login.
     */
    @ModelAttribute("currentUser")
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    public Map<String, String> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
            || !auth.isAuthenticated()
            || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return null;
        }
        AppUser user = principal.getAppUser();
        String name = user.getName() != null
            ? user.getName()
            : user.getEmail() != null ? user.getEmail() : "";
        String avatarUrl = user.getAvatarUrl() != null ? user.getAvatarUrl() : "";
        return Map.of("name", name, "avatarUrl", avatarUrl);
    }
}
