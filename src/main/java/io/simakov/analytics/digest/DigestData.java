package io.simakov.analytics.digest;

import java.util.List;

/**
 * Aggregated weekly data for one workspace, passed to the email template.
 * If teams are configured, {@link #teamSections()} is non-empty and the template
 * renders per-team blocks; otherwise it falls back to a single workspace-wide section.
 */
public record DigestData(
    String workspaceName,
    String recipientName,
    String recipientEmail,
    String periodLabel,
    String appUrl,

    // ── Workspace-level KPI (always shown at the top) ─────────────────────
    int mrCount,
    int prevMrCount,
    Double ttmMedianHours,
    Double prevTtmMedianHours,
    int deploysCount,

    // ── Per-team breakdown (empty = no teams configured, use workspace view) ─
    List<TeamSection> teamSections,

    // ── Workspace-wide insights ────────────────────────────────────────────
    List<InsightRow> insights
) {

    /** Returns percentage change, or null if prev is 0. */
    public static Integer pctChange(int current, int prev) {
        if (prev == 0) {
            return null;
        }
        return (int) Math.round((current - prev) * 100.0 / prev);
    }

    /** Returns percentage change for doubles, or null if prev is 0/null. */
    public static Integer pctChangeDouble(Double current, Double prev) {
        if (prev == null || prev == 0 || current == null) {
            return null;
        }
        return (int) Math.round((current - prev) * 100.0 / prev);
    }

    public record TeamSection(
        String teamName,
        int colorIndex,
        int mrCount,
        int prevMrCount,
        Double ttmMedianHours,
        Double prevTtmMedianHours,
        List<ContributorRow> topContributors
    ) {
    }

    public record ContributorRow(String name, int mrCount, Double ttmHours) {
    }

    public record InsightRow(String kind, String title) {
    }
}
