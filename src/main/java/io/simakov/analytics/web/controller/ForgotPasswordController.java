package io.simakov.analytics.web.controller;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.PasswordResetToken;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.PasswordResetTokenRepository;
import io.simakov.analytics.security.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class ForgotPasswordController {

    private final AppUserRepository appUserRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String email, Model model) {
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        Optional<AppUser> userOpt = appUserRepository.findByEmail(normalizedEmail);
        // Always show the same message to prevent email enumeration
        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();
            String token = UUID.randomUUID().toString().replace("-", "");
            tokenRepository.save(PasswordResetToken.builder()
                .appUserId(user.getId())
                .token(token)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .used(false)
                .build());
            emailService.sendPasswordResetEmail(normalizedEmail, token);
        }
        model.addAttribute("sent", true);
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        Optional<PasswordResetToken> resetToken = tokenRepository.findByToken(token);
        if (resetToken.isEmpty()
            || resetToken.get().isUsed()
            || resetToken.get().getExpiresAt().isBefore(Instant.now())) {
            return "redirect:/login?tokenExpired";
        }
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                @RequestParam String password,
                                @RequestParam String confirmPassword,
                                Model model) {
        if (!password.equals(confirmPassword)) {
            model.addAttribute("token", token);
            model.addAttribute("error", "Пароли не совпадают.");
            return "reset-password";
        }
        Optional<PasswordResetToken> resetTokenOpt = tokenRepository.findByToken(token);
        if (resetTokenOpt.isEmpty()
            || resetTokenOpt.get().isUsed()
            || resetTokenOpt.get().getExpiresAt().isBefore(Instant.now())) {
            return "redirect:/login?tokenExpired";
        }
        PasswordResetToken resetToken = resetTokenOpt.get();
        Optional<AppUser> userOpt = appUserRepository.findById(resetToken.getAppUserId());
        if (userOpt.isEmpty()) {
            return "redirect:/login?error";
        }
        AppUser user = userOpt.get();
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmailVerified(true);
        appUserRepository.save(user);

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        return "redirect:/login?passwordReset";
    }
}
