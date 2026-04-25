package io.simakov.analytics.digest;

import io.simakov.analytics.digest.DigestData.ContributorRow;
import io.simakov.analytics.digest.DigestData.InsightRow;
import io.simakov.analytics.digest.DigestData.ServiceRow;
import io.simakov.analytics.digest.DigestData.TeamSection;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Dev-only endpoint to preview the digest email template in the browser.
 * Available at GET /dev/digest-preview
 */
@Controller
@Profile("dev")
public class DigestPreviewController {

    @GetMapping("/dev/digest-preview")
    public String preview(Model model) {
        DigestData data = new DigestData(
            "Uzum Backend",
            "Игорь Симаков",
            "igor@example.com",
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
        model.addAttribute("d", data);
        return "email/digest";
    }
}
