package io.simakov.analytics.domain.model.enums;

/**
 * Four-phase sync model:
 * <ul>
 *   <li>{@link #FAST}    — Phase 1: MR list only (no commits / notes / approvals / diffs).
 *       Completes in ~2-5 minutes even for large repos. Gives MR count + TTM immediately.</li>
 *   <li>{@link #ENRICH}  — Phase 2: full enrichment (commits, notes, approvals, net-diff).
 *       Auto-triggered after FAST completes. Runs in the background while user sees the dashboard.</li>
 *   <li>{@link #RELEASE} — Phase 3: release sync (GitLab releases API → prod deploy timestamps → MR attribution).
 *       Auto-triggered after ENRICH completes. Workspace-level idempotency: only one RELEASE job at a time.</li>
 *   <li>{@link #JIRA_INCIDENTS} — Phase 4: Jira incident sync (fetches incidents, matches to projects by component).
 *       Auto-triggered after RELEASE completes if Jira is configured. Workspace-level, one job at a time.</li>
 * </ul>
 */
public enum SyncJobPhase {
    FAST,
    ENRICH,
    RELEASE,
    JIRA_INCIDENTS
}
