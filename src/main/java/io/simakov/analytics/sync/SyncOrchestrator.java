package io.simakov.analytics.sync;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.api.exception.GitLabApiException;
import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestDiscussion;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestApprovalRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
import io.simakov.analytics.domain.repository.MergeRequestDiscussionRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabApprovalsDto;
import io.simakov.analytics.gitlab.dto.GitLabCommitDto;
import io.simakov.analytics.gitlab.dto.GitLabDiscussionDto;
import io.simakov.analytics.gitlab.dto.GitLabMergeRequestDto;
import io.simakov.analytics.gitlab.dto.GitLabNoteDto;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import io.simakov.analytics.gitlab.mapper.GitLabMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncOrchestrator {

    private final GitLabApiClient gitLabApiClient;
    private final GitLabMapper gitLabMapper;
    private final EncryptionService encryptionService;

    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestCommitRepository commitRepository;
    private final MergeRequestDiscussionRepository discussionRepository;
    private final MergeRequestNoteRepository noteRepository;
    private final MergeRequestApprovalRepository approvalRepository;

    private final SyncJobService syncJobService;

    @Async("syncTaskExecutor")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void orchestrateAsync(Long jobId,
                                 ManualSyncRequest request) {
        log.info("Starting sync job {} for projects {} from {} to {}",
            jobId, request.projectIds(), request.dateFrom(), request.dateTo());
        try {
            for (Long projectId : request.projectIds()) {
                syncProject(jobId, projectId, request);
            }
            syncJobService.complete(jobId);
        } catch (Exception e) {
            log.error("Sync job {} failed with error: {}", jobId, e.getMessage(), e);
            syncJobService.fail(jobId, e.getMessage());
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private void syncProject(Long jobId,
                             Long trackedProjectId,
                             ManualSyncRequest request) {
        TrackedProject project = trackedProjectRepository.findById(trackedProjectId)
            .orElseThrow(() -> new IllegalArgumentException("TrackedProject not found: " + trackedProjectId));

        GitSource source = gitSourceRepository.findById(project.getGitSourceId())
            .orElseThrow(() -> new IllegalArgumentException("GitSource not found: " + project.getGitSourceId()));

        String token = encryptionService.decrypt(project.getTokenEncrypted());
        String baseUrl = source.getBaseUrl();

        log.info("Syncing project '{}' (gitlabId={})", project.getPathWithNamespace(), project.getGitlabProjectId());

        List<GitLabMergeRequestDto> mrDtos = gitLabApiClient.getMergeRequests(
            baseUrl, token, project.getGitlabProjectId(), request.dateFrom(), request.dateTo());

        int total = mrDtos.size();
        log.info("Fetched {} MRs for project '{}'", total, project.getPathWithNamespace());
        syncJobService.updateProgress(jobId, 0, total);

        int processed = 0;
        for (GitLabMergeRequestDto mrDto : mrDtos) {
            try {
                syncMergeRequest(mrDto, trackedProjectId, baseUrl, token, request);
            } catch (Exception e) {
                log.warn("Failed to sync MR iid={} in project {}: {}",
                    mrDto.iid(), project.getPathWithNamespace(), e.getMessage());
            }
            syncJobService.updateProgress(jobId, ++processed, total);
        }
    }

    private void syncMergeRequest(GitLabMergeRequestDto mrDto,
                                  Long trackedProjectId,
                                  String baseUrl,
                                  String token,
                                  ManualSyncRequest request) {
        MergeRequest mr = mergeRequestRepository
            .findByTrackedProjectIdAndGitlabMrId(trackedProjectId, mrDto.id())
            .orElse(null);

        if (mr == null) {
            mr = gitLabMapper.toMergeRequest(mrDto, trackedProjectId);
        } else {
            gitLabMapper.updateMergeRequest(mr, mrDto);
        }
        mr = mergeRequestRepository.save(mr);

        Long mrId = mr.getId();
        Long mrIid = mr.getGitlabMrIid();
        Long gitlabProjectId = getGitlabProjectId(trackedProjectId);

        if (request.fetchCommits()) {
            syncCommits(baseUrl, token, gitlabProjectId, mrIid, mrId);
        }

        if (request.fetchNotes()) {
            syncDiscussions(baseUrl, token, gitlabProjectId, mrIid, mrId);
        }

        if (request.fetchApprovals()) {
            syncApprovals(baseUrl, token, gitlabProjectId, mrIid, mrId, mr.getAuthorGitlabUserId());
        }
    }

    private void syncCommits(String baseUrl,
                             String token,
                             Long gitlabProjectId,
                             Long mrIid,
                             Long mrId) {
        List<GitLabCommitDto> commits = gitLabApiClient.getMergeRequestCommits(baseUrl, token, gitlabProjectId, mrIid);
        for (GitLabCommitDto dto : commits) {
            boolean exists = commitRepository
                .findByMergeRequestIdAndGitlabCommitSha(mrId, dto.id()).isPresent();
            if (!exists) {
                GitLabCommitDto withStats = gitLabApiClient
                    .getCommitWithStats(baseUrl, token, gitlabProjectId, dto.id());
                commitRepository.save(gitLabMapper.toCommit(withStats, mrId));
            }
        }
    }

    private void syncDiscussions(String baseUrl,
                                 String token,
                                 Long gitlabProjectId,
                                 Long mrIid,
                                 Long mrId) {
        List<GitLabDiscussionDto> discussions = gitLabApiClient.getMergeRequestDiscussions(
            baseUrl, token, gitlabProjectId, mrIid);

        for (GitLabDiscussionDto discussionDto : discussions) {
            MergeRequestDiscussion discussion = discussionRepository
                .findByMergeRequestIdAndGitlabDiscussionId(mrId, discussionDto.id())
                .orElseGet(() -> discussionRepository.save(gitLabMapper.toDiscussion(discussionDto, mrId)));

            if (discussionDto.notes() != null) {
                for (GitLabNoteDto noteDto : discussionDto.notes()) {
                    noteRepository.findByMergeRequestIdAndGitlabNoteId(mrId, noteDto.id())
                        .orElseGet(() -> noteRepository.save(
                            gitLabMapper.toNote(noteDto, mrId, discussion.getId())));
                }
            }
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void syncApprovals(String baseUrl,
                               String token,
                               Long gitlabProjectId,
                               Long mrIid,
                               Long mrId,
                               Long authorGitlabUserId) {
        try {
            GitLabApprovalsDto approvalsDto = gitLabApiClient.getMergeRequestApprovals(
                baseUrl, token, gitlabProjectId, mrIid);

            if (approvalsDto == null || approvalsDto.approvedBy() == null) {
                return;
            }

            for (GitLabApprovalsDto.ApproverEntry entry : approvalsDto.approvedBy()) {
                GitLabUserDto user = entry.user();
                if (user == null || user.id() == null) {
                    continue;
                }

                approvalRepository.findByMergeRequestIdAndApprovedByGitlabUserId(mrId, user.id())
                    .orElseGet(() -> approvalRepository.save(gitLabMapper.toApproval(user, mrId)));
            }
        } catch (GitLabApiException e) {
            // Approvals API may not be available on all GitLab plans or for all users
            log.warn("Approvals not available for MR {} (mrIid={}): {}", mrId, mrIid, e.getMessage());
        }
    }

    private Long getGitlabProjectId(Long trackedProjectId) {
        return trackedProjectRepository.findById(trackedProjectId)
            .map(TrackedProject::getGitlabProjectId)
            .orElseThrow(() -> new IllegalArgumentException("TrackedProject not found: " + trackedProjectId));
    }
}
