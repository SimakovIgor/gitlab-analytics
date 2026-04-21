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
 * Fires for users whose rework ratio (MRs requiring post-review edits / total MRs) exceeds the threshold.
 * A high rework ratio may indicate unclear requirements, insufficient pre-review preparation,
 * or overly strict review standards.
 */
@Component
@RequiredArgsConstructor
public class HighReworkRatioEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> offenders = ctx.current().values().stream()
            .filter(m -> !m.isInactive())
            .filter(m -> m.getMrMergedCount() >= 3)
            .filter(m -> m.getReworkRatio() > props.getMaxReworkRatio())
            .sorted((a, b) -> Double.compare(b.getReworkRatio(), a.getReworkRatio()))
            .toList();

        if (offenders.isEmpty()) {
            return List.of();
        }

        String names = offenders.stream()
            .limit(3)
            .map(m -> String.format("%s (%.0f%%)", m.getDisplayName(), m.getReworkRatio() * 100))
            .collect(Collectors.joining(", "));

        List<Long> affectedIds = offenders.stream()
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format(
            "Высокая доля доработок у %d %s",
            offenders.size(),
            offenders.size() == 1
                ? "участника"
                : "участников"
        );
        String body = String.format(
            "%s. Высокий rework ratio означает, что MR часто требуют существенных правок после открытия ревью.",
            names
        );

        return List.of(TeamInsight.of(InsightRule.HIGH_REWORK_RATIO, title, body, affectedIds));
    }
}
