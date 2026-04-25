package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
import io.simakov.analytics.workspace.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class JoinController {

    public static final String SESSION_PENDING_INVITE = "pendingInviteToken";

    private final InviteService inviteService;

    @GetMapping("/join")
    public String join(@RequestParam String token,
                       HttpServletRequest request,
                       Model model) {
        Optional<WorkspaceInvite> invite = inviteService.findValid(token);
        if (invite.isEmpty()) {
            model.addAttribute("error", "Ссылка недействительна или устарела.");
            return "join-error";
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
            && auth.getPrincipal() instanceof AppUserPrincipal;

        if (authenticated) {
            AppUserPrincipal principal = (AppUserPrincipal) auth.getPrincipal();
            Long appUserId = principal.getAppUser().getId();
            inviteService.consumeInvite(invite.get(), appUserId);
            HttpSession session = request.getSession();
            session.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID, invite.get().getWorkspaceId());
            log.info("Authenticated user {} joined workspace {} via invite link", appUserId, invite.get().getWorkspaceId());
            return "redirect:/report";
        }

        // Not authenticated — store token in session and redirect to login
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_PENDING_INVITE, token);
        return "redirect:/login";
    }
}
