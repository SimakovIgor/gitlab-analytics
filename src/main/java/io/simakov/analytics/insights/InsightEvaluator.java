package io.simakov.analytics.insights;

import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.TeamInsight;

import java.util.List;

/**
 * Contract for a single algorithmic insight rule.
 * <p>
 * Each implementation is a Spring {@code @Component} and is automatically collected by
 * {@link InsightService}. Evaluators must be stateless and side-effect-free.
 * <p>
 * Return an empty list when the rule does not trigger (never return {@code null}).
 */
public interface InsightEvaluator {

    /**
     * Evaluate the rule against the provided context.
     *
     * @param ctx pre-built data bundle with current/previous metrics and open MRs
     * @return list of triggered insights, empty if the rule does not fire
     */
    List<TeamInsight> evaluate(InsightContext ctx);
}
