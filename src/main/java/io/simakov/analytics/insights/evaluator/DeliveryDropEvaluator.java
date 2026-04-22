package io.simakov.analytics.insights.evaluator;

import io.simakov.analytics.insights.InsightEvaluator;
import io.simakov.analytics.insights.InsightProperties;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.InsightRule;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.model.UserMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fires when the team's total merged MR count dropped significantly compared to the previous period.
 * Useful for spotting unusual slow-downs (holidays, incidents, blockers).
 */
@Component
@RequiredArgsConstructor
public class DeliveryDropEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        int currTotal = ctx.current().values().stream()
            .mapToInt(UserMetrics::getMrMergedCount)
            .sum();
        int prevTotal = ctx.previous().values().stream()
            .mapToInt(UserMetrics::getMrMergedCount)
            .sum();

        if (prevTotal == 0) {
            return List.of();
        }

        double ratio = (double) currTotal / prevTotal;
        if (ratio >= props.getDeliveryDropRatio()) {
            return List.of();
        }

        int dropPct = (int) Math.round((1.0 - ratio) * 100);

        String title = String.format(
            "Объём влитых MR упал на %d%% — %d против %d в прошлом периоде",
            dropPct, currTotal, prevTotal
        );
        String body = String.format(
            "Команда смерджила %d MR в текущем периоде и %d в предыдущем. Снижение на %d%% может говорить"
                + " о накопившемся техдолге, блокировках или нехватке ресурсов.",
            currTotal, prevTotal, dropPct
        );

        return List.of(TeamInsight.of(InsightRule.DELIVERY_DROP, title, body, List.of()));
    }
}
