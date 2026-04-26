package io.simakov.analytics.digest;

import io.simakov.analytics.digest.DigestData.ContributorRow;
import io.simakov.analytics.digest.DigestData.InsightRow;
import io.simakov.analytics.digest.DigestData.ServiceRow;
import io.simakov.analytics.digest.DigestData.TeamSection;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Dev-only endpoints to preview and test the digest email.
 * GET  /dev/digest-preview         — renders template in browser
 * POST /dev/send-digest-preview    — sends mock email via configured SMTP (Mailhog)
 * POST /dev/send-digest            — triggers real digest for all enabled workspaces
 */
@Controller
@Profile("dev")
@RequiredArgsConstructor
public class DigestPreviewController {

    private final DigestEmailSender digestEmailSender;
    private final WeeklyDigestScheduler weeklyDigestScheduler;

    @GetMapping("/dev/digest-preview")
    public String preview(Model model) {
        model.addAttribute("d", buildMockData("igor@example.com"));
        return "email/digest";
    }

    @PostMapping("/dev/send-digest-preview")
    @ResponseBody
    public ResponseEntity<String> sendPreview(
            @RequestParam(defaultValue = "test@example.com") String to) {
        digestEmailSender.send(buildMockData(to));
        return ResponseEntity.ok("Mock digest sent to " + to + " — check http://localhost:8025");
    }

    /** Triggers the real weekly digest for all workspaces with digest_enabled=true. */
    @PostMapping("/dev/send-digest")
    @ResponseBody
    public ResponseEntity<String> sendReal() {
        weeklyDigestScheduler.sendWeeklyDigests();
        return ResponseEntity.ok("Real digest triggered — check http://localhost:8025");
    }

    private DigestData buildMockData(String recipientEmail) {
        return new DigestData(
            "Uzum Backend",
            "Игорь Симаков",
            recipientEmail,
            "19 — 25 апр 2026",
            "http://localhost:8080",
            47,
            39,
            18.4,
            23.1,
            6,
            List.of(
                new ServiceRow("payment-service", 3.0, 2.1, 0.0),
                new ServiceRow("catalog-api", 2.0, 4.5, null),
                new ServiceRow("user-service", 1.0, 1.8, 5.0),
                new ServiceRow("notification-svc", 0.0, null, null)
            ),
            List.of(
                new TeamSection(
                    "Backend",
                    1,
                    34,
                    28,
                    18.4,
                    23.1,
                    List.of(
                        new ContributorRow("a.ivanov", 12, 14.2),
                        new ContributorRow("m.petrov", 9, 22.7),
                        new ContributorRow("k.sidorova", 8, 17.5)
                    )
                ),
                new TeamSection(
                    "Frontend",
                    2,
                    13,
                    11,
                    31.0,
                    28.5,
                    List.of(
                        new ContributorRow("kozlov", 7, 29.0),
                        new ContributorRow("v.morozov", 4, 19.8),
                        new ContributorRow("d.kuznetsov", 2, 44.0)
                    )
                ),
                new TeamSection(
                    "Без команды",
                    0,
                    3,
                    0,
                    null,
                    null,
                    List.of(
                        new ContributorRow("temp.contractor", 3, null)
                    )
                )
            ),
            List.of(
                new InsightRow("BAD", "3 MR открыты без движения более 72 часов"),
                new InsightRow("WARN", "Медиана TTM выросла на 28% по сравнению с прошлым периодом"),
                new InsightRow("WARN", "Нагрузка на ревью распределена неравномерно — коэффициент Джини 0.71"),
                new InsightRow("INFO", "Средний размер MR: 487 строк — выше рекомендуемого порога"),
                new InsightRow("GOOD", "Deploy Frequency соответствует уровню Elite по DORA")
            )
        );
    }
}
