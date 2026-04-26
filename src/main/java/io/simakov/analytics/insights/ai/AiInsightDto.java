package io.simakov.analytics.insights.ai;

/**
 * A single AI-generated insight returned to the UI.
 *
 * @param kind  severity category matching {@link io.simakov.analytics.insights.model.InsightKind} CSS values:
 *              {@code "bad"}, {@code "warn"}, {@code "good"}, {@code "info"}
 * @param title short Russian title (max ~80 chars)
 * @param body  1–2 sentence Russian explanation
 */
public record AiInsightDto(String kind, String title, String body) {

}
