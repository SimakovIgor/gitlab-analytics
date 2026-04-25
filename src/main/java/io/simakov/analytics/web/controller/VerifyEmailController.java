package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class VerifyEmailController {

    private final AppUserRepository appUserRepository;

    @GetMapping("/verify-email-sent")
    public String verifyEmailSent() {
        return "verify-email-sent";
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam String token,
                              @RequestParam(required = false) String invite,
                              HttpServletRequest request) {
        Optional<AppUser> userOpt = appUserRepository.findByEmailVerificationToken(token);
        if (userOpt.isEmpty()) {
            return "redirect:/login?invalidToken";
        }
        AppUser user = userOpt.get();
        if (user.getEmailVerificationExpiresAt() != null
            && user.getEmailVerificationExpiresAt().isBefore(Instant.now())) {
            return "redirect:/login?tokenExpired";
        }
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiresAt(null);
        appUserRepository.save(user);

        // Preserve pending invite across browser tabs — store in the current session so
        // WorkspaceAwareSuccessHandler can consume it after the user logs in
        if (invite != null) {
            HttpSession session = request.getSession(true);
            session.setAttribute(JoinController.SESSION_PENDING_INVITE, invite);
        }

        return "redirect:/login?verified";
    }
}
