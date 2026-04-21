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
 * Fires when the team's average median TTM grew by more than {@code mergeTimeSpikeRatio}×
 * compared to the previous period.
 */
@Component
@RequiredArgsConstructor
public class MergeTimeSpikeEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        double currAvg = averageMedianTtm(ctx.current().values().stream().toList());
        double prevAvg = averageMedianTtm(ctx.previous().values().stream().toList());

        if (currAvg == 0 || prevAvg == 0) {
            return List.of();
        }

        double ratio = currAvg / prevAvg;
        if (ratio < props.getMergeTimeSpikeRatio()) {
            return List.of();
        }

        double ratioRounded = Math.round(ratio * 10) / 10.0;
        double currHours = Math.round(currAvg / 60.0 * 10) / 10.0;
        double prevHours = Math.round(prevAvg / 60.0 * 10) / 10.0;

        List<Long> affected = ctx.current().values().stream()
            .filter(m -> m.getMedianTimeToMergeMinutes() != null && !m.isInactive())
            .sorted((a, b) -> Double.compare(b.getMedianTimeToMergeMinutes(), a.getMedianTimeToMergeMinutes()))
            .limit(3)
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format("Медиана TTM выросла в %.1f раза", ratioRounded);
        String body = String.format(
            "Среднее медианное время до мержа: %.1f ч в текущем периоде против %.1f ч в предыдущем."
                + " Возможные причины: накопившаяся очередь ревью, блокировки по внешним зависимостям.",
            currHours, prevHours
        );

        return List.of(TeamInsight.of(InsightRule.MERGE_TIME_SPIKE, title, body, affected));
    }

    private double averageMedianTtm(List<UserMetrics> metrics) {
        return metrics.stream()
            .filter(m -> m.getMedianTimeToMergeMinutes() != null && !m.isInactive())
            .mapToDouble(UserMetrics::getMedianTimeToMergeMinutes)
            .average()
            .orElse(0);
    }
}
