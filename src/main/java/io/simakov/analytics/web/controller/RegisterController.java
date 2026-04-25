package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.security.WorkspaceAwareSuccessHandler;
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

@Controller
@RequiredArgsConstructor
public class RegisterController {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

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

        AppUser user = appUserRepository.save(AppUser.builder()
            .name(name.trim())
            .email(normalizedEmail)
            .passwordHash(passwordEncoder.encode(password))
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
        // workspaceId not set yet — WorkspaceAwareSuccessHandler logic replicated:
        // no workspace means redirect to /onboarding
        session.removeAttribute(WorkspaceAwareSuccessHandler.SESSION_WORKSPACE_ID);

        return "redirect:/onboarding";
    }
}
