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
 * Fires for active users who merge MRs but do not participate in reviewing others.
 * Only checked for users above the {@code minMrsForNoReviewCheck} threshold to avoid
 * false positives for low-activity team members.
 */
@Component
@RequiredArgsConstructor
public class NoCodeReviewEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> offenders = ctx.current().values().stream()
            .filter(m -> m.getMrMergedCount() >= props.getMinMrsForNoReviewCheck())
            .filter(m -> m.getMrsReviewedCount() == 0)
            .toList();

        if (offenders.isEmpty()) {
            return List.of();
        }

        String names = offenders.stream()
            .limit(3)
            .map(m -> String.format("%s (%d MR)", m.getDisplayName(), m.getMrMergedCount()))
            .collect(Collectors.joining(", "));

        List<Long> affectedIds = offenders.stream()
            .map(UserMetrics::getTrackedUserId)
            .toList();

        String title = String.format(
            "%d %s мержит MR, но не ревьюит чужие",
            offenders.size(),
            offenders.size() == 1
                ? "участник"
                : "участника"
        );
        String body = String.format(
            "%s. Отсутствие участия в ревью создаёт дисбаланс нагрузки и снижает качество knowledge sharing.",
            names
        );

        return List.of(TeamInsight.of(InsightRule.NO_CODE_REVIEW, title, body, affectedIds));
    }
}
