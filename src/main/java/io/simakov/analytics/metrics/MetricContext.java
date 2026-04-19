package io.simakov.analytics.metrics;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.TrackedUser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of all data needed to compute metrics for a single user.
 * Pre-loaded and pre-filtered by {@link MetricCalculationService} before providers are invoked.
 *
 * @param user           the tracked user being measured
 * @param aliasEmails    lowercased emails: user.email + all alias emails
 * @param gitlabIds      GitLab user IDs resolved from aliases
 * @param authoredMrs    MRs authored by this user in the period
 * @param userCommits    deduplicated commits authored by this user (by SHA) across authoredMrs
 * @param notesByMrId    all notes for all MRs in scope, keyed by MR internal DB id
 * @param approvalsByMrId all approvals for all MRs in scope, keyed by MR internal DB id
 * @param commitsByMrId  all commits for all MRs in scope, keyed by MR internal DB id
 */
public record MetricContext(
    TrackedUser user,
    Set<String> aliasEmails,
    Set<Long> gitlabIds,
    List<MergeRequest> authoredMrs,
    List<MergeRequestCommit> userCommits,
    Map<Long, List<MergeRequestNote>> notesByMrId,
    Map<Long, List<MergeRequestApproval>> approvalsByMrId,
    Map<Long, List<MergeRequestCommit>> commitsByMrId
) {
}
