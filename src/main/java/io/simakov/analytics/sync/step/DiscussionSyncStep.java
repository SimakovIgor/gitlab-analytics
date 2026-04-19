package io.simakov.analytics.sync.step;

import io.simakov.analytics.api.dto.request.ManualSyncRequest;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestDiscussion;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.repository.MergeRequestDiscussionRepository;
import io.simakov.analytics.domain.repository.MergeRequestNoteRepository;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabDiscussionDto;
import io.simakov.analytics.gitlab.dto.GitLabNoteDto;
import io.simakov.analytics.gitlab.mapper.GitLabMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Syncs discussions and notes for a MergeRequest.
 * Pre-loads all existing discussion IDs and note IDs in two queries,
 * avoiding N individual existence-check queries per discussion/note.
 */
@Component
@Order(2)
@RequiredArgsConstructor
class DiscussionSyncStep implements SyncStep {

    private final GitLabApiClient gitLabApiClient;
    private final GitLabMapper gitLabMapper;
    private final MergeRequestDiscussionRepository discussionRepository;
    private final MergeRequestNoteRepository noteRepository;

    @Override
    public boolean isEnabled(ManualSyncRequest request) {
        return request.fetchNotes();
    }

    @Override
    public void sync(SyncContext ctx, MergeRequest mr) {
        List<GitLabDiscussionDto> discussions = gitLabApiClient.getMergeRequestDiscussions(
            ctx.baseUrl(), ctx.token(), ctx.gitlabProjectId(), mr.getGitlabMrIid());

        Map<String, MergeRequestDiscussion> existingByGitlabId = discussionRepository
            .findByMergeRequestId(mr.getId())
            .stream()
            .collect(Collectors.toMap(MergeRequestDiscussion::getGitlabDiscussionId, d -> d));

        Set<Long> existingNoteIds = noteRepository.findByMergeRequestIdIn(List.of(mr.getId()))
            .stream()
            .map(MergeRequestNote::getGitlabNoteId)
            .collect(Collectors.toSet());

        for (GitLabDiscussionDto discussionDto : discussions) {
            MergeRequestDiscussion discussion = existingByGitlabId.containsKey(discussionDto.id())
                ? existingByGitlabId.get(discussionDto.id())
                : discussionRepository.save(gitLabMapper.toDiscussion(discussionDto, mr.getId()));

            if (discussionDto.notes() == null) {
                continue;
            }
            for (GitLabNoteDto noteDto : discussionDto.notes()) {
                if (noteDto == null || noteDto.id() == null) {
                    continue;
                }
                if (!existingNoteIds.contains(noteDto.id())) {
                    noteRepository.save(gitLabMapper.toNote(noteDto, mr.getId(), discussion.getId()));
                }
            }
        }
    }
}
