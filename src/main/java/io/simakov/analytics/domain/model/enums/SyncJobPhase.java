package io.simakov.analytics.domain.model.enums;

/**
 * Two-phase sync model:
 * <ul>
 *   <li>{@link #FAST}   — Phase 1: MR list only (no commits / notes / approvals / diffs).
 *       Completes in ~2-5 minutes even for large repos. Gives MR count + TTM immediately.</li>
 *   <li>{@link #ENRICH} — Phase 2: full enrichment (commits, notes, approvals, net-diff).
 *       Auto-triggered after FAST completes. Runs in the background while user sees the dashboard.</li>
 * </ul>
 */
public enum SyncJobPhase {
    FAST,
    ENRICH
}
