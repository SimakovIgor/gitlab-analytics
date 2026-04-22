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
 * Fires when the DORA Deploy Frequency falls below the configured threshold.
 */
@Component
@RequiredArgsConstructor
public class LowDeployFrequencyEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    private static String formatFrequency(double deploysPerDay) {
        if (deploysPerDay >= 1.0) {
            return String.format("%.1f/день", deploysPerDay);
        }
        double perWeek = deploysPerDay * 7;
        if (perWeek >= 1.0) {
            return String.format("%.1f/неделю", perWeek);
        }
        return String.format("%.1f/месяц", deploysPerDay * 30);
    }

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        Double deploysPerDay = ctx.deploysPerDay();
        if (deploysPerDay == null) {
            return List.of();
        }

        double threshold = props.getMinDeploysPerDay();
        if (deploysPerDay >= threshold) {
            return List.of();
        }

        String displayFreq = formatFrequency(deploysPerDay);
        String displayTarget = formatFrequency(threshold);

        String title = String.format("DORA Deploy Frequency — %s (цель: ≥ %s)", displayFreq, displayTarget);
        String body = String.format(
            "Частота деплоев в прод составляет %s,"
                + " что ниже целевого показателя %s."
                + " Рассмотрите: уменьшение размера релизов, автоматизацию CI/CD, feature flags.",
            displayFreq, displayTarget
        );

        return List.of(TeamInsight.of(InsightRule.LOW_DEPLOY_FREQUENCY, title, body, List.of()));
    }
}
