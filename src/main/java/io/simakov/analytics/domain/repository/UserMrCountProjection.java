package io.simakov.analytics.domain.repository;

/**
 * Projection for MR count per tracked user, returned by
 * {@link MergeRequestRepository#countMrsByTrackedUser}.
 */
public interface UserMrCountProjection {

    Long getTrackedUserId();

    Long getMrCount();
}
