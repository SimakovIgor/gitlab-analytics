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
 * Populates net diff stats (additions / deletions) for a MergeRequest.
 *
 * <p>Primary source: {@code diff_stats_summary} field in the GitLab MR object (GitLab 14.10+).
 * When available, this field is set during the FAST phase by {@link io.simakov.analytics.gitlab.mapper.GitLabMapper}
 * and contains server-computed totals that include all files — including those too large to diff.
 *
 * <p>Fallback: {@code GET /merge_requests/:iid/diffs} — parses unified diff text line-by-line.
 * Files with {@code too_large=true} contribute 0, which may under-count vs. GitLab UI.
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
    public void sync(SyncContext ctx,
                     MergeRequest mr) {
        if (mr.getNetAdditions() != null) {
            // Already set from diff_stats_summary during FAST phase — skip expensive /diffs parsing.
            log.debug("Skipping /diffs for MR iid={}: netAdditions already set to {}",
                mr.getGitlabMrIid(), mr.getNetAdditions());
            return;
        }
        MrNetDiffStats stats = gitLabApiClient.getMrNetDiffStats(
            ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), mr.getGitlabMrIid());
        mr.setNetAdditions(stats.additions());
        mr.setNetDeletions(stats.deletions());
        mergeRequestRepository.save(mr);
        log.debug("Net diff for MR iid={} via /diffs fallback: +{} -{}",
            mr.getGitlabMrIid(), stats.additions(), stats.deletions());
    }
}
