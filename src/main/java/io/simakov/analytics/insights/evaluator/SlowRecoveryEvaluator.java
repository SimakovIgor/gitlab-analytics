package io.simakov.analytics.insights.evaluator;

import io.simakov.analytics.insights.InsightEvaluator;
import io.simakov.analytics.insights.InsightProperties;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.InsightRule;
import io.simakov.analytics.insights.model.TeamInsight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fires when the DORA MTTR (Mean Time To Recovery)
 * exceeds the configured threshold.
 */
@Component
@RequiredArgsConstructor
public class SlowRecoveryEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        Double mttr = ctx.mttrHours();
        if (mttr == null) {
            return List.of();
        }

        double threshold = props.getMaxMttrHours();
        if (mttr <= threshold) {
            return List.of();
        }

        String title = String.format(
            "DORA MTTR — %.1f ч (цель: ≤ %.0f ч)", mttr, threshold
        );
        String body = String.format(
            "Среднее время восстановления после инцидента составляет %.1f часов,"
                + " что превышает целевой показатель %.0f часов."
                + " Проверьте: скорость обнаружения инцидентов, процесс эскалации,"
                + " наличие runbook'ов и готовность дежурной смены.",
            mttr, threshold
        );

        return List.of(TeamInsight.of(InsightRule.SLOW_RECOVERY, title, body, List.of()));
    }
}
