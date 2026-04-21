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
 * Fires when one or more active users have an average MR size exceeding the configured threshold.
 * Large MRs are harder to review and correlate with longer time-to-merge.
 */
@Component
@RequiredArgsConstructor
public class LargeMrHabitEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> offenders = ctx.current().values().stream()
            .filter(m -> !m.isInactive())
            .filter(m -> m.getMrMergedCount() >= 2)
            .filter(m -> m.getAvgMrSizeLines() > props.getLargeMrLines())
            .sorted((a, b) -> Double.compare(b.getAvgMrSizeLines(), a.getAvgMrSizeLines()))
            .toList();

        if (offenders.isEmpty()) {
            return List.of();
        }

        String names = offenders.stream()
            .limit(3)
            .map(m -> String.format("%s (ср. %.0f строк)", m.getDisplayName(), m.getAvgMrSizeLines()))
            .collect(Collectors.joining(", "));

        List<Long> affectedIds = offenders.stream()
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format(
            "%d %s с крупными MR (> %d строк в среднем)",
            offenders.size(),
            offenders.size() == 1 ? "участник" : "участника",
            props.getLargeMrLines()
        );
        String body = String.format(
            "%s. Большие MR мержатся значительно дольше и труднее ревьюируются — рекомендуется разбивать на меньшие части.",
            names
        );

        return List.of(TeamInsight.of(InsightRule.LARGE_MR_HABIT, title, body, affectedIds));
    }
}
