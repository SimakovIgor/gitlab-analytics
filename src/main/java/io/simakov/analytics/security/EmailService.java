package io.simakov.analytics.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Sends transactional emails via Resend HTTP API (https://resend.com).
 * Does not use SMTP — works on servers where outgoing SMTP ports are blocked.
 *
 * <p>Enabled when {@code RESEND_API_KEY} env var is set.
 * When absent, {@link #isEnabled()} returns {@code false} and all send-methods
 * log the token instead — useful for local dev without credentials.
 */
@Slf4j
@Service
public class EmailService {

    private final WebClient webClient;
    private final String from;
    private final String baseUrl;
    private final String apiKey;
    private final String resendApiUrl;

    public EmailService(WebClient.Builder webClientBuilder,
                        @Value("${app.mail.from:noreply@gitpulse.ru}") String from,
                        @Value("${app.base-url:http://localhost:8080}") String baseUrl,
                        @Value("${RESEND_API_KEY:}") String apiKey,
                        @Value("${app.resend.api-url:https://api.resend.com/emails}") String resendApiUrl) {
        this.webClient = webClientBuilder.build();
        this.from = from;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.resendApiUrl = resendApiUrl;
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }

    public void sendVerificationEmail(String toEmail, String token, String pendingInviteToken) {
        if (!isEnabled()) {
            log.info("Resend disabled — verification token for {}: {}", toEmail, token);
            return;
        }
        String link = baseUrl + "/verify-email?token=" + token
            + (pendingInviteToken != null ? "&invite=" + pendingInviteToken : "");
        String body = "Здравствуйте!\n\n"
            + "Для завершения регистрации перейдите по ссылке:\n"
            + link + "\n\n"
            + "Ссылка действует 24 часа.\n\n"
            + "Если вы не регистрировались на GitPulse — проигнорируйте это письмо.\n\n"
            + "— Команда GitPulse";
        send(toEmail, "GitPulse — подтвердите email", body);
        log.debug("Verification email sent to {}", toEmail);
    }

    public void sendPasswordResetEmail(String toEmail, String token) {
        if (!isEnabled()) {
            log.info("Resend disabled — password reset token for {}: {}", toEmail, token);
            return;
        }
        String link = baseUrl + "/reset-password?token=" + token;
        String body = "Вы запросили сброс пароля.\n\n"
            + "Перейдите по ссылке для создания нового пароля:\n"
            + link + "\n\n"
            + "Ссылка действует 1 час.\n\n"
            + "Если вы не запрашивали сброс — проигнорируйте это письмо.\n\n"
            + "— Команда GitPulse";
        send(toEmail, "GitPulse — сброс пароля", body);
        log.debug("Password reset email sent to {}", toEmail);
    }

    private void send(String toEmail, String subject, String text) {
        Map<String, Object> payload = Map.of(
            "from", from,
            "to", List.of(toEmail),
            "subject", subject,
            "text", text
        );
        webClient.post()
            .uri(resendApiUrl)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .toBodilessEntity()
            .block();
    }
}
