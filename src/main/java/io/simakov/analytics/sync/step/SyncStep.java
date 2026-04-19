package io.simakov.analytics.sync.step;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.domain.model.MergeRequest;

/**
 * Strategy for syncing one aspect (commits / discussions / approvals) of a single MergeRequest.
 *
 * <p>All implementations are Spring beans collected by {@code SyncOrchestrator} into a
 * {@code List<SyncStep>} (sorted by {@link org.springframework.core.annotation.Order}).
 */
public interface SyncStep {

    /** Returns {@code true} when this step should run for the given sync request. */
    boolean isEnabled(ManualSyncRequest request);

    /** Performs the sync using the supplied GitLab context and persists results to the DB. */
    void sync(SyncContext ctx, MergeRequest mr);
}
