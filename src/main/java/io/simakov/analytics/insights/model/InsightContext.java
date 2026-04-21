package io.simakov.analytics.insights.model;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.metrics.model.UserMetrics;

import java.util.List;
import java.util.Map;

/**
 * Data bundle passed to every {@link io.simakov.analytics.insights.InsightEvaluator}.
 * Built once by {@link io.simakov.analytics.insights.InsightService} and shared across all evaluators.
 *
 * @param users                       all tracked users in the workspace
 * @param current                     metrics for the selected period, keyed by {@link TrackedUser#getId()}
 * @param previous                    metrics for the immediately preceding period of the same length
 * @param openMrs                     MRs currently in OPENED state for the selected projects
 * @param gitlabUserIdToTrackedUserId mapping from GitLab user ID to TrackedUser ID,
 *                                    built from tracked_user_alias; used to resolve MR authors
 */
public record InsightContext(
    List<TrackedUser> users,
    Map<Long, UserMetrics> current,
    Map<Long, UserMetrics> previous,
    List<MergeRequest> openMrs,
    Map<Long, Long> gitlabUserIdToTrackedUserId
) {

}
