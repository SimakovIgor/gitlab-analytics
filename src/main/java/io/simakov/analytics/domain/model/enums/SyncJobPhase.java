package io.simakov.analytics.domain.model.enums;

/**
 * Sync phases:
 * <ul>
 *   <li>{@link #FAST}    — Phase 1: MR list only (no commits / notes / approvals / diffs).
 *       Completes in ~2-5 minutes even for large repos. Gives MR count + TTM immediately.</li>
 *   <li>{@link #ENRICH}  — Phase 2: full enrichment (commits, notes, approvals, net-diff).
 *       Auto-triggered after FAST completes. Runs in the background while user sees the dashboard.</li>
 *   <li>{@link #RELEASE} — Release sync (GitLab releases API → prod deploy timestamps → MR attribution).
 *       Triggered manually from DORA page. Per-project idempotency.</li>
 *   <li>{@link #JIRA_INCIDENTS} — Jira incident sync (fetches incidents, matches to projects by component).
 *       Triggered manually from DORA page or auto-chained after RELEASE. Workspace-level, one job at a time.</li>
 * </ul>
 */
public enum SyncJobPhase {
    FAST,
    ENRICH,
    RELEASE,
    JIRA_INCIDENTS
}
