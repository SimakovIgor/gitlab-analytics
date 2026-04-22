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
 * Fires when the team's average median time-to-merge exceeds the configured threshold.
 * Uses {@link UserMetrics#getMedianTimeToMergeMinutes()} averaged across active users.
 */
@Component
@RequiredArgsConstructor
public class HighMergeTimeEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> withTtm = ctx.current().values().stream()
            .filter(m -> m.getMedianTimeToMergeMinutes() != null)
            .filter(m -> !m.isInactive())
            .toList();

        if (withTtm.isEmpty()) {
            return List.of();
        }

        double avgMedianMinutes = withTtm.stream()
            .mapToDouble(UserMetrics::getMedianTimeToMergeMinutes)
            .average()
            .orElse(0);

        double thresholdMinutes = props.getMaxMedianTtmHours() * 60;

        if (avgMedianMinutes <= thresholdMinutes) {
            return List.of();
        }

        double avgMedianHours = Math.round(avgMedianMinutes / 60.0 * 10) / 10.0;
        double targetHours = props.getMaxMedianTtmHours();

        List<Long> slowest = withTtm.stream()
            .sorted((a, b) -> Double.compare(b.getMedianTimeToMergeMinutes(), a.getMedianTimeToMergeMinutes()))
            .limit(3)
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format("Медиана Time to Merge команды — %.1f ч (цель: %.0f ч)", avgMedianHours, targetHours);
        String body = String.format(
            "Среднее медианное время от создания MR до мержа в dev составляет %.1f ч,"
                + " что превышает целевой показатель %.0f ч."
                + " Проверьте узкие места: ожидание ревью, блокировки CI/CD.",
            avgMedianHours, targetHours
        );

        return List.of(TeamInsight.of(InsightRule.HIGH_MERGE_TIME, title, body, slowest));
    }
}
