package io.simakov.analytics.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Sends transactional emails (verification, password reset).
 * When {@code SPRING_MAIL_HOST} env var is not set, {@link JavaMailSender} is not
 * auto-created; {@link #isEnabled()} returns {@code false} and all send-methods
 * log the token instead — useful for local dev without SMTP credentials.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    /** Absent when SPRING_MAIL_HOST env var is not configured. */
    private final Optional<JavaMailSender> mailSender;

    @Value("${app.mail.from:noreply@gitpulse.ru}")
    private String from;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public boolean isEnabled() {
        return mailSender.isPresent();
    }

    public void sendVerificationEmail(String toEmail, String token, String pendingInviteToken) {
        if (mailSender.isEmpty()) {
            log.info("Mail disabled — verification token for {}: {}", toEmail, token);
            return;
        }
        String link = baseUrl + "/verify-email?token=" + token
            + (pendingInviteToken != null ? "&invite=" + pendingInviteToken : "");
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(toEmail);
        msg.setSubject("GitPulse — подтвердите email");
        msg.setText(
            "Здравствуйте!\n\n"
            + "Для завершения регистрации перейдите по ссылке:\n"
            + link + "\n\n"
            + "Ссылка действует 24 часа.\n\n"
            + "Если вы не регистрировались на GitPulse — проигнорируйте это письмо.\n\n"
            + "— Команда GitPulse"
        );
        mailSender.get().send(msg);
        log.debug("Verification email sent to {}", toEmail);
    }

    public void sendPasswordResetEmail(String toEmail, String token) {
        if (mailSender.isEmpty()) {
            log.info("Mail disabled — password reset token for {}: {}", toEmail, token);
            return;
        }
        String link = baseUrl + "/reset-password?token=" + token;
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(toEmail);
        msg.setSubject("GitPulse — сброс пароля");
        msg.setText(
            "Вы запросили сброс пароля.\n\n"
            + "Перейдите по ссылке для создания нового пароля:\n"
            + link + "\n\n"
            + "Ссылка действует 1 час.\n\n"
            + "Если вы не запрашивали сброс — проигнорируйте это письмо.\n\n"
            + "— Команда GitPulse"
        );
        mailSender.get().send(msg);
        log.debug("Password reset email sent to {}", toEmail);
    }
}
