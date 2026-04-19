package io.simakov.analytics.sync.step;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.exception.GitLabApiException;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabApprovalsDto;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import io.simakov.analytics.gitlab.mapper.GitLabMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Syncs approvals for a MergeRequest.
 * Pre-loads all existing approver IDs in one query before iterating,
 * avoiding N individual existence-check queries.
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
class ApprovalSyncStep implements SyncStep {

    private final GitLabApiClient gitLabApiClient;
    private final GitLabMapper gitLabMapper;
    private final MergeRequestApprovalRepository approvalRepository;

    @Override
    public boolean isEnabled(ManualSyncRequest request) {
        return request.fetchApprovals();
    }

    @Override
    public void sync(SyncContext ctx, MergeRequest mr) {
        try {
            GitLabApprovalsDto approvalsDto = gitLabApiClient.getMergeRequestApprovals(
                ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), mr.getGitlabMrIid());

            if (approvalsDto == null || approvalsDto.approvedBy() == null) {
                return;
            }

            Set<Long> existingApproverIds = approvalRepository.findByMergeRequestIdIn(List.of(mr.getId()))
                .stream()
                .map(MergeRequestApproval::getApprovedByGitlabUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

            for (GitLabApprovalsDto.ApproverEntry entry : approvalsDto.approvedBy()) {
                GitLabUserDto user = entry.user();
                if (user == null || user.id() == null) {
                    continue;
                }
                if (!existingApproverIds.contains(user.id())) {
                    approvalRepository.save(gitLabMapper.toApproval(user, mr.getId()));
                }
            }
        } catch (GitLabApiException e) {
            log.warn("Approvals not available for MR {} (mrIid={}): {}",
                mr.getId(), mr.getGitlabMrIid(), e.getMessage());
        }
    }
}
