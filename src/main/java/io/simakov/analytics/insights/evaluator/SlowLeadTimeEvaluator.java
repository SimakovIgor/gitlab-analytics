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
 * Fires when the DORA Lead Time for Changes (MR created → prod deploy)
 * exceeds the configured threshold.
 */
@Component
@RequiredArgsConstructor
public class SlowLeadTimeEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        Double median = ctx.leadTimeMedianDays();
        if (median == null) {
            return List.of();
        }

        double threshold = props.getMaxLeadTimeDays();
        if (median <= threshold) {
            return List.of();
        }

        String title = String.format(
            "DORA Lead Time — %.1f дн (цель: ≤ %.0f дн)", median, threshold
        );
        String body = String.format(
            "Медиана времени от создания MR до деплоя в прод составляет %.1f дней,"
                + " что превышает целевой показатель %.0f дней."
                + " Проверьте: время ожидания ревью, длительность стейджинга, ручные этапы деплоя.",
            median, threshold
        );

        return List.of(TeamInsight.of(InsightRule.SLOW_LEAD_TIME, title, body, List.of()));
    }
}
