package io.simakov.analytics.insights.evaluator;

import io.simakov.analytics.insights.InsightEvaluator;
import io.simakov.analytics.insights.InsightProperties;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.InsightRule;
import io.simakov.analytics.insights.model.TeamInsight;
import io.simakov.analytics.metrics.model.UserMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Fires when the Gini coefficient of the review load distribution exceeds the configured threshold.
 * A high Gini means a small number of people are doing most of the reviewing.
 * <p>
 * Gini coefficient: 0 = perfect equality, 1 = one person does all reviews.
 */
@Component
@RequiredArgsConstructor
public class ReviewLoadImbalanceEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    /**
     * Computes the Gini coefficient for a sorted list of non-negative integers.
     * Uses the standard discrete formula: G = (2 * Σ(i * x_i) / (n * Σx_i)) - (n+1)/n
     */
    public static double computeGini(List<Integer> sorted) {
        int n = sorted.size();
        long sum = sorted.stream().mapToLong(Integer::longValue).sum();
        if (sum == 0) {
            return 0;
        }
        long weightedSum = 0;
        for (int i = 0; i < n; i++) {
            weightedSum += (i + 1L) * sorted.get(i);
        }
        return (2.0 * weightedSum / (n * sum)) - (double) (n + 1) / n;
    }

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        List<UserMetrics> active = ctx.current().values().stream()
            .filter(m -> !m.isInactive())
            .toList();

        if (active.size() < 2) {
            return List.of();
        }

        List<Integer> counts = active.stream()
            .map(UserMetrics::getMrsReviewedCount)
            .sorted()
            .toList();

        double gini = computeGini(counts);
        if (gini < props.getReviewGini()) {
            return List.of();
        }

        int totalReviews = counts.stream().mapToInt(Integer::intValue).sum();
        UserMetrics topReviewer = active.stream()
            .max(Comparator.comparingInt(UserMetrics::getMrsReviewedCount))
            .orElseThrow();
        double topPct = totalReviews > 0
            ? Math.round(100.0 * topReviewer.getMrsReviewedCount() / totalReviews)
            : 0;

        String title = String.format(
            "Review load перекошен: Gini = %.2f (порог %.2f)",
            gini, props.getReviewGini()
        );
        String body = String.format(
            "%s делает %d ревью (%.0f%% всех). Перегруженный ревьюер — узкое место"
                + " и риск выгорания. Рекомендуется ротация.",
            topReviewer.getDisplayName(), topReviewer.getMrsReviewedCount(), topPct
        );

        List<Long> affected = List.of(topReviewer.getTrackedUserId());
        return List.of(TeamInsight.of(InsightRule.REVIEW_LOAD_IMBALANCE, title, body, affected));
    }
}
