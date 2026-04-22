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
import java.util.stream.Collectors;

/**
 * Fires for active team members whose merged MR count is below 30% of the team median.
 * Completely inactive users (0 MR, 0 commits) are excluded — they are likely
 * departed employees or stale accounts, not a team health concern.
 */
@Component
@RequiredArgsConstructor
public class LowActivityEvaluator implements InsightEvaluator {

    private static final double LOW_ACTIVITY_RATIO = 0.3;

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> active = ctx.current().values().stream()
            .filter(m -> !m.isInactive())
            .toList();

        if (active.size() < 2) {
            return List.of();
        }

        double median = medianMrCount(active);
        if (median <= 0) {
            return List.of();
        }

        double threshold = median * LOW_ACTIVITY_RATIO;

        List<UserMetrics> low = active.stream()
            .filter(m -> m.getMrMergedCount() > 0 && m.getMrMergedCount() < threshold)
            .toList();

        if (low.isEmpty()) {
            return List.of();
        }

        String names = low.stream()
            .limit(5)
            .map(UserMetrics::getDisplayName)
            .collect(Collectors.joining(", "));

        List<Long> affectedIds = low.stream()
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format(
            "%d %s со слабой активностью за период",
            low.size(),
            low.size() == 1
                ? "участник"
                : "участника"
        );
        String body = String.format(
            "%s%s. Количество влитых MR ниже 30%% от медианы команды (%.0f MR)."
                + " Возможные причины: переключение на другие задачи, блокировки, онбординг.",
            names,
            low.size() > 5
                ? " и другие"
                : "",
            median
        );

        return List.of(TeamInsight.of(InsightRule.LOW_ACTIVITY, title, body, affectedIds));
    }

    private double medianMrCount(List<UserMetrics> active) {
        List<Integer> sorted = active.stream()
            .map(UserMetrics::getMrMergedCount)
            .sorted()
            .toList();
        int n = sorted.size();
        if (n % 2 == 0) {
            return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }
        return sorted.get(n / 2);
    }
}
