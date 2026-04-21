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
 * Fires when the team average comments-per-reviewed-MR is below the configured threshold.
 * A low value may indicate shallow or rubber-stamp reviews.
 * Only users who actually performed reviews are included in the average.
 */
@Component
@RequiredArgsConstructor
public class LowReviewDepthEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> reviewers = ctx.current().values().stream()
            .filter(m -> m.getMrsReviewedCount() > 0)
            .toList();

        if (reviewers.isEmpty()) {
            return List.of();
        }

        double avgCommentsPerMr = reviewers.stream()
            .mapToDouble(UserMetrics::getCommentsPerReviewedMr)
            .average()
            .orElse(0);

        if (avgCommentsPerMr >= props.getMinCommentsPerMr()) {
            return List.of();
        }

        double rounded = Math.round(avgCommentsPerMr * 10) / 10.0;

        String title = String.format(
            "Глубина ревью низкая: %.1f комментария на MR (цель ≥ %.1f)",
            rounded, props.getMinCommentsPerMr()
        );
        String body = String.format(
            "В среднем команда оставляет %.1f комментария на отревьюенный MR."
                + " Это может говорить о поверхностных проверках или аппрувах без обратной связи.",
            rounded
        );

        return List.of(TeamInsight.of(InsightRule.LOW_REVIEW_DEPTH, title, body, List.of()));
    }
}
