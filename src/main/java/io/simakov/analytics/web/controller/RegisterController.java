package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.EmailService;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
import io.simakov.analytics.workspace.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class RegisterController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final InviteService inviteService;

    @GetMapping("/register")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String name,
                           @RequestParam String email,
                           @RequestParam String password,
                           HttpServletRequest request,
                           Model model) {
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);

        if (appUserRepository.findByEmail(normalizedEmail).isPresent()) {
            model.addAttribute("error", "Пользователь с таким email уже зарегистрирован.");
            model.addAttribute("name", name);
            model.addAttribute("email", email);
            return "register";
        }

        HttpSession session = request.getSession(false);
        String pendingInvite = session != null
            ? (String) session.getAttribute(JoinController.SESSION_PENDING_INVITE)
            : null;

        if (emailService.isEnabled()) {
            return registerWithVerification(name, normalizedEmail, password, pendingInvite);
        }
        return registerAndAutoLogin(name, normalizedEmail, password, request, pendingInvite);
    }

    private String registerWithVerification(String name, String normalizedEmail, String password,
                                             String pendingInvite) {
        String token = UUID.randomUUID().toString().replace("-", "");
        appUserRepository.save(AppUser.builder()
            .name(name.trim())
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(password))
            .emailVerified(false)
            .emailVerificationToken(token)
            .emailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
            .lastLoginAt(Instant.now())
            .build());
        // Embed pending invite into verification link so it survives across browser tabs/sessions
        emailService.sendVerificationEmail(normalizedEmail, token, pendingInvite);
        return "redirect:/verify-email-sent";
    }

    private String registerAndAutoLogin(String name, String normalizedEmail, String password,
                                        HttpServletRequest request, String pendingInvite) {
        AppUser user = appUserRepository.save(AppUser.builder()
            .name(name.trim())
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(password))
            .emailVerified(true)
            .lastLoginAt(Instant.now())
            .build());

        AppUserPrincipal principal = new AppUserPrincipal(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            principal, null, principal.getAuthorities());

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);

        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, ctx);
        session.removeAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID);
        session.removeAttribute(JoinController.SESSION_PENDING_INVITE);

        // Consume invite immediately — WorkspaceAwareSuccessHandler is not invoked for manual auto-login
        if (pendingInvite != null) {
            Optional<WorkspaceInvite> invite = inviteService.findValid(pendingInvite);
            if (invite.isPresent()) {
                inviteService.consumeInvite(invite.get(), user.getId());
                session.setAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID,
                    invite.get().getWorkspaceId());
                return "redirect:/report";
            }
        }

        return "redirect:/onboarding";
    }
}
