package io.simakov.analytics.sync.step;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.MrNetDiffStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fetches net diff stats (additions / deletions) from GET /merge_requests/:iid/diffs.
 * These match what GitLab UI shows on the Changes tab and are stored in
 * merge_request.net_additions / merge_request.net_deletions.
 *
 * <p>Unlike commit stats (sum of per-commit additions/deletions), net diff reflects
 * the actual code change introduced by the MR against its base branch.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
class MrDiffStatsSyncStep implements SyncStep {

    private final GitLabApiClient gitLabApiClient;
    private final MergeRequestRepository mergeRequestRepository;

    @Override
    public boolean isEnabled(ManualSyncRequest request) {
        return request.fetchDiffStats();
    }

    @Override
    public void sync(SyncContext ctx, MergeRequest mr) {
        MrNetDiffStats stats = gitLabApiClient.getMrNetDiffStats(
            ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), mr.getGitlabMrIid());
        mr.setNetAdditions(stats.additions());
        mr.setNetDeletions(stats.deletions());
        mergeRequestRepository.save(mr);
        log.debug("Net diff for MR iid={}: +{} -{}", mr.getGitlabMrIid(), stats.additions(), stats.deletions());
    }
}
