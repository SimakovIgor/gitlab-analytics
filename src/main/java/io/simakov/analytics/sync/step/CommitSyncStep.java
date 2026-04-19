package io.simakov.analytics.sync.step;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabCommitDto;
import io.simakov.analytics.gitlab.mapper.GitLabMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Syncs commits for a MergeRequest.
 * Loads all existing commit SHAs in one query before iterating,
 * avoiding N individual existence-check queries.
 */
@Component
@Order(1)
@RequiredArgsConstructor
class CommitSyncStep implements SyncStep {

    private final GitLabApiClient gitLabApiClient;
    private final GitLabMapper gitLabMapper;
    private final MergeRequestCommitRepository commitRepository;

    @Override
    public boolean isEnabled(ManualSyncRequest request) {
        return request.fetchCommits();
    }

    @Override
    public void sync(SyncContext ctx, MergeRequest mr) {
        List<GitLabCommitDto> commits = gitLabApiClient.getMergeRequestCommits(
            ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), mr.getGitlabMrIid());

        Set<String> existingShas = commitRepository.findByMergeRequestIdIn(List.of(mr.getId()))
            .stream()
            .map(MergeRequestCommit::getGitlabCommitSha)
            .collect(Collectors.toSet());

        for (GitLabCommitDto dto : commits) {
            if (!existingShas.contains(dto.id())) {
                GitLabCommitDto withStats = gitLabApiClient
                    .getCommitWithStats(ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), dto.id());
                commitRepository.save(gitLabMapper.toCommit(withStats, mr.getId()));
            }
        }
    }
}
