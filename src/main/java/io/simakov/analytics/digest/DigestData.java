package io.simakov.analytics.digest;

import java.util.List;

/**
 * Aggregated weekly data for one workspace, passed to the email template.
 */
public record DigestData(
    String workspaceName,
    String recipientName,
    String recipientEmail,
    String periodLabel,
    String appUrl,

    // ── KPI ───────────────────────────────────────────────────────────────
    int mrCount,
    int prevMrCount,
    Double ttmMedianHours,
    Double prevTtmMedianHours,
    int deploysCount,

    // ── Top contributors (up to 5) ────────────────────────────────────────
    List<ContributorRow> topContributors,

    // ── Active insights (up to 5, sorted by severity desc) ───────────────
    List<InsightRow> insights
) {

    /** Returns percentage change between prev and current, or null if prev is 0. */
    public static Integer pctChange(int current, int prev) {
        if (prev == 0) {
            return null;
        }
        return (int) Math.round((current - prev) * 100.0 / prev);
    }

    /** Returns percentage change between prev and current double values, or null if prev is 0/null. */
    public static Integer pctChangeDouble(Double current, Double prev) {
        if (prev == null || prev == 0 || current == null) {
            return null;
        }
        return (int) Math.round((current - prev) * 100.0 / prev);
    }

    public record ContributorRow(String name, int mrCount, Double ttmHours) {
    }

    public record InsightRow(String kind, String title) {
    }
}
