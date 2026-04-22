package io.simakov.analytics.insights.evaluator;

import io.simakov.analytics.insights.InsightEvaluator;
import io.simakov.analytics.insights.InsightProperties;
import io.simakov.analytics.insights.model.InsightContext;
import io.simakov.analytics.insights.model.InsightRule;
import io.simakov.analytics.insights.model.TeamInsight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fires when the DORA Lead Time for Changes grew significantly
 * compared to the previous period of the same length.
 */
@Component
@RequiredArgsConstructor
public class LeadTimeRegressionEvaluator implements InsightEvaluator {

    private final InsightProperties props;

    @Override
    public List<TeamInsight> evaluate(InsightContext ctx) {
        Double current = ctx.leadTimeMedianDays();
        Double previous = ctx.prevLeadTimeMedianDays();

        if (current == null || previous == null || previous == 0) {
            return List.of();
        }

        double ratio = current / previous;
        if (ratio < props.getLeadTimeRegressionRatio()) {
            return List.of();
        }

        double ratioRounded = Math.round(ratio * 10) / 10.0;

        String title = String.format(
            "DORA Lead Time вырос в %.1f раза (%.1f → %.1f дн)",
            ratioRounded, previous, current
        );
        String body = String.format(
            "Медиана Lead Time for Changes выросла с %.1f до %.1f дней (×%.1f)."
                + " Возможные причины: замедление ревью, задержки на стейджинге,"
                + " увеличение размера релизов.",
            previous, current, ratioRounded
        );

        return List.of(TeamInsight.of(InsightRule.LEAD_TIME_REGRESSION, title, body, List.of()));
    }
}
