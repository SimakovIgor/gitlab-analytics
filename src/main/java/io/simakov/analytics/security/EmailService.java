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
        String html = buildVerificationHtml(link);
        sendHtml(toEmail, "GitPulse — подтвердите email", html);
        log.debug("Verification email sent to {}", toEmail);
    }

    public void sendPasswordResetEmail(String toEmail, String token) {
        if (!isEnabled()) {
            log.info("Resend disabled — password reset token for {}: {}", toEmail, token);
            return;
        }
        String link = baseUrl + "/reset-password?token=" + token;
        String html = buildPasswordResetHtml(link);
        sendHtml(toEmail, "GitPulse — сброс пароля", html);
        log.debug("Password reset email sent to {}", toEmail);
    }

    @SuppressWarnings("checkstyle:MethodLength")
    private String buildVerificationHtml(String link) {
        return "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Подтвердите email</title></head>"
            + "<body style=\"margin:0;padding:0;background:#f1eee7;"
            + "font-family:-apple-system,'Segoe UI',Arial,sans-serif;\">"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
            + " style=\"background:#f1eee7;padding:40px 16px;\">"
            + "<tr><td align=\"center\">"
            + "<table width=\"520\" cellpadding=\"0\" cellspacing=\"0\""
            + " style=\"max-width:520px;width:100%;border-radius:16px;overflow:hidden;"
            + "box-shadow:0 4px 28px rgba(22,21,15,0.11);\">"

            // Header
            + "<tr><td style=\"background:linear-gradient(150deg,#1a1740 0%,#2d2a6e 100%);"
            + "padding:32px 36px 28px;border-radius:16px 16px 0 0;\">"
            + "<table cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + "<td style=\"vertical-align:middle;padding-right:10px;\">"
            + "<svg width=\"28\" height=\"28\" viewBox=\"0 0 32 32\" fill=\"none\">"
            + "<polygon points=\"16,4 28,10.5 28,22.5 16,29 4,22.5 4,10.5\""
            + " fill=\"none\" stroke=\"rgba(160,150,255,0.55)\" stroke-width=\"1.5\"/>"
            + "<polyline points=\"8,20 13,13 18,17 24,9\" stroke=\"#a09cff\" stroke-width=\"2\""
            + " stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>"
            + "<circle cx=\"24\" cy=\"9\" r=\"2.5\" fill=\"#a09cff\"/>"
            + "</svg></td>"
            + "<td style=\"vertical-align:middle;\">"
            + "<span style=\"font-size:16px;font-weight:700;color:#fff;letter-spacing:-0.2px;\">GitPulse</span>"
            + "</td></tr></table>"
            + "<div style=\"margin-top:20px;font-size:11px;font-weight:700;"
            + "text-transform:uppercase;letter-spacing:1px;color:rgba(160,150,255,0.8);\">Регистрация</div>"
            + "<div style=\"margin-top:4px;font-size:22px;font-weight:800;color:#fff;"
            + "letter-spacing:-0.5px;line-height:1.2;\">Подтвердите ваш email</div>"
            + "</td></tr>"

            // Body
            + "<tr><td style=\"background:#fff;padding:32px 36px 28px;border-top:3px solid #5046cf;\">"
            + "<p style=\"margin:0 0 20px;font-size:15px;color:#3a3730;line-height:1.6;\">"
            + "Вы почти на месте! Нажмите кнопку ниже, чтобы завершить регистрацию "
            + "и начать отслеживать метрики вашей команды.</p>"

            // CTA button
            + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"margin:28px 0;\">"
            + "<tr><td style=\"border-radius:10px;background:#5046cf;\">"
            + "<a href=\"" + link + "\""
            + " style=\"display:inline-block;padding:14px 32px;font-size:15px;font-weight:700;"
            + "color:#fff;text-decoration:none;border-radius:10px;"
            + "letter-spacing:-0.1px;\">Подтвердить email →</a>"
            + "</td></tr></table>"

            // Hint row
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
            + " style=\"background:#f7f5f0;border-radius:10px;margin:0 0 20px;\">"
            + "<tr>"
            + "<td width=\"44\" style=\"padding:14px 0 14px 16px;vertical-align:middle;\">"
            + "<span style=\"font-size:22px;\">⏱</span>"
            + "</td>"
            + "<td style=\"padding:14px 16px 14px 10px;\">"
            + "<span style=\"font-size:13px;color:#5a5648;line-height:1.4;\">Ссылка действует <strong>24 часа</strong>. "
            + "После истечения срока потребуется повторная регистрация.</span>"
            + "</td>"
            + "</tr>"
            + "</table>"

            + "<p style=\"margin:0;font-size:12px;color:#9a9488;line-height:1.5;\">"
            + "Если вы не регистрировались на GitPulse — просто проигнорируйте это письмо.</p>"
            + "</td></tr>"

            // Footer
            + "<tr><td style=\"background:#f7f5f0;padding:18px 36px;border-radius:0 0 16px 16px;\">"
            + "<p style=\"margin:0;font-size:12px;color:#9a9488;\">"
            + "© GitPulse · <a href=\"https://gitpulse.ru\""
            + " style=\"color:#5046cf;text-decoration:none;\">gitpulse.ru</a></p>"
            + "</td></tr>"

            + "</table></td></tr></table>"
            + "</body></html>";
    }

    private String buildPasswordResetHtml(String link) {
        return "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Сброс пароля</title></head>"
            + "<body style=\"margin:0;padding:0;background:#f1eee7;"
            + "font-family:-apple-system,'Segoe UI',Arial,sans-serif;\">"
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
            + " style=\"background:#f1eee7;padding:40px 16px;\">"
            + "<tr><td align=\"center\">"
            + "<table width=\"520\" cellpadding=\"0\" cellspacing=\"0\""
            + " style=\"max-width:520px;width:100%;border-radius:16px;overflow:hidden;"
            + "box-shadow:0 4px 28px rgba(22,21,15,0.11);\">"

            // Header
            + "<tr><td style=\"background:linear-gradient(150deg,#1a1740 0%,#2d2a6e 100%);"
            + "padding:32px 36px 28px;border-radius:16px 16px 0 0;\">"
            + "<table cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + "<td style=\"vertical-align:middle;padding-right:10px;\">"
            + "<svg width=\"28\" height=\"28\" viewBox=\"0 0 32 32\" fill=\"none\">"
            + "<polygon points=\"16,4 28,10.5 28,22.5 16,29 4,22.5 4,10.5\""
            + " fill=\"none\" stroke=\"rgba(160,150,255,0.55)\" stroke-width=\"1.5\"/>"
            + "<polyline points=\"8,20 13,13 18,17 24,9\" stroke=\"#a09cff\" stroke-width=\"2\""
            + " stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>"
            + "<circle cx=\"24\" cy=\"9\" r=\"2.5\" fill=\"#a09cff\"/>"
            + "</svg></td>"
            + "<td style=\"vertical-align:middle;\">"
            + "<span style=\"font-size:16px;font-weight:700;color:#fff;letter-spacing:-0.2px;\">GitPulse</span>"
            + "</td></tr></table>"
            + "<div style=\"margin-top:20px;font-size:11px;font-weight:700;"
            + "text-transform:uppercase;letter-spacing:1px;color:rgba(160,150,255,0.8);\">Безопасность</div>"
            + "<div style=\"margin-top:4px;font-size:22px;font-weight:800;color:#fff;"
            + "letter-spacing:-0.5px;line-height:1.2;\">Сброс пароля</div>"
            + "</td></tr>"

            // Body
            + "<tr><td style=\"background:#fff;padding:32px 36px 28px;border-top:3px solid #5046cf;\">"
            + "<p style=\"margin:0 0 20px;font-size:15px;color:#3a3730;line-height:1.6;\">"
            + "Мы получили запрос на сброс пароля для вашего аккаунта. "
            + "Нажмите кнопку ниже, чтобы создать новый пароль.</p>"

            // CTA button
            + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"margin:28px 0;\">"
            + "<tr><td style=\"border-radius:10px;background:#5046cf;\">"
            + "<a href=\"" + link + "\""
            + " style=\"display:inline-block;padding:14px 32px;font-size:15px;font-weight:700;"
            + "color:#fff;text-decoration:none;border-radius:10px;"
            + "letter-spacing:-0.1px;\">Создать новый пароль →</a>"
            + "</td></tr></table>"

            // Hint row
            + "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
            + " style=\"background:#f7f5f0;border-radius:10px;margin:0 0 20px;\">"
            + "<tr>"
            + "<td width=\"44\" style=\"padding:14px 0 14px 16px;vertical-align:middle;\">"
            + "<span style=\"font-size:22px;\">⏱</span>"
            + "</td>"
            + "<td style=\"padding:14px 16px 14px 10px;\">"
            + "<span style=\"font-size:13px;color:#5a5648;line-height:1.4;\">Ссылка действует <strong>1 час</strong>. "
            + "Если не успеете — запросите сброс повторно.</span>"
            + "</td>"
            + "</tr>"
            + "</table>"

            + "<p style=\"margin:0;font-size:12px;color:#9a9488;line-height:1.5;\">"
            + "Если вы не запрашивали сброс пароля — просто проигнорируйте это письмо. "
            + "Ваш пароль останется прежним.</p>"
            + "</td></tr>"

            // Footer
            + "<tr><td style=\"background:#f7f5f0;padding:18px 36px;border-radius:0 0 16px 16px;\">"
            + "<p style=\"margin:0;font-size:12px;color:#9a9488;\">"
            + "© GitPulse · <a href=\"https://gitpulse.ru\""
            + " style=\"color:#5046cf;text-decoration:none;\">gitpulse.ru</a></p>"
            + "</td></tr>"

            + "</table></td></tr></table>"
            + "</body></html>";
    }

    private void sendHtml(String toEmail, String subject, String html) {
        Map<String, Object> payload = Map.of(
            "from", from,
            "to", List.of(toEmail),
            "subject", subject,
            "html", html
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
