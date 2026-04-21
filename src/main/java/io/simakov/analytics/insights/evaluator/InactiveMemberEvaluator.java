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
 * Fires for tracked users who have zero activity (MRs merged, commits, reviews) in the selected period.
 * Useful for spotting stale team members or misconfigured aliases.
 */
@Component
@RequiredArgsConstructor
public class InactiveMemberEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> inactive = ctx.current().values().stream()
            .filter(UserMetrics::isInactive)
            .toList();

        if (inactive.isEmpty()) {
            return List.of();
        }

        String names = inactive.stream()
            .limit(5)
            .map(UserMetrics::getDisplayName)
            .collect(Collectors.joining(", "));

        List<Long> affectedIds = inactive.stream()
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format(
            "%d %s без активности за период",
            inactive.size(),
            inactive.size() == 1 ? "участник" : "участника"
        );
        String body = String.format(
            "%s%s. Возможные причины: отпуск, больничный, неверная настройка email-алиасов"
                + " или устаревшая запись в системе.",
            names,
            inactive.size() > 5 ? " и другие" : ""
        );

        return List.of(TeamInsight.of(InsightRule.INACTIVE_MEMBER, title, body, affectedIds));
    }
}
