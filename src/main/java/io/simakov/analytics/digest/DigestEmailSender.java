package io.simakov.analytics.digest;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DigestEmailSender {

    private final Optional<JavaMailSender> mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.mail.from:noreply@gitpulse.ru}")
    private String from;

    public void send(DigestData data) {
        if (mailSender.isEmpty()) {
            log.info("Mail disabled — digest for workspace '{}' recipient={}", data.workspaceName(), data.recipientEmail());
            return;
        }

        Context ctx = new Context(new java.util.Locale("ru"));
        ctx.setVariable("d", data);

        String html = templateEngine.process("email/digest", ctx);

        try {
            MimeMessage msg = mailSender.get().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(data.recipientEmail());
            helper.setSubject("Дайджест команды · " + data.workspaceName() + " · " + data.periodLabel());
            helper.setText(html, true);
            mailSender.get().send(msg);
            log.debug("Digest sent to {} for workspace '{}'", data.recipientEmail(), data.workspaceName());
        } catch (MessagingException e) {
            log.error("Failed to send digest to {}: {}", data.recipientEmail(), e.getMessage(), e);
        }
    }
}
