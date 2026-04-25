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

    // ── Per-service DORA breakdown ─────────────────────────────────────────
    List<ServiceRow> services,

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
        public String colorHex() {
            return switch (colorIndex) {
                case 1 -> "#5046cf";
                case 2 -> "#3d9e6c";
                case 3 -> "#e67e22";
                case 4 -> "#c0392b";
                case 5 -> "#2980b9";
                case 6 -> "#8e44ad";
                default -> "#8a8573";
            };
        }
    }

    public record ContributorRow(String name, int mrCount, Double ttmHours) {
    }

    public record InsightRow(String kind, String title) {
    }

    /** Per-service DORA snapshot shown in the digest services table. */
    public record ServiceRow(
        String name,
        double deploysPerWeek,
        Double leadTimeDays,
        Double cfrPercent
    ) {
        public String deploysColor() {
            if (deploysPerWeek >= 3) {
                return "#3d9e6c";
            }
            if (deploysPerWeek >= 1) {
                return "#e67e22";
            }
            return "#d1cec5";
        }

        public String cfrColor() {
            if (cfrPercent == null) {
                return "#b5b09c";
            }
            if (cfrPercent == 0) {
                return "#3d9e6c";
            }
            if (cfrPercent <= 15) {
                return "#e67e22";
            }
            return "#c0392b";
        }
    }
}
