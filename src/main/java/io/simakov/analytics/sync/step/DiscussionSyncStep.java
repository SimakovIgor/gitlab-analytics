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

import java.util.ArrayList;
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

        saveNewDiscussions(discussions, mr.getId(), existingByGitlabId);
        saveNewNotes(discussions, mr.getId(), existingByGitlabId, existingNoteIds);
    }

    private void saveNewDiscussions(List<GitLabDiscussionDto> discussions,
                                    Long mrId,
                                    Map<String, MergeRequestDiscussion> existingByGitlabId) {
        List<MergeRequestDiscussion> toSave = new ArrayList<>();
        for (GitLabDiscussionDto dto : discussions) {
            if (!existingByGitlabId.containsKey(dto.id())) {
                toSave.add(gitLabMapper.toDiscussion(dto, mrId));
            }
        }
        if (!toSave.isEmpty()) {
            discussionRepository.saveAll(toSave).forEach(
                saved -> existingByGitlabId.put(saved.getGitlabDiscussionId(), saved));
        }
    }

    private void saveNewNotes(List<GitLabDiscussionDto> discussions,
                              Long mrId,
                              Map<String, MergeRequestDiscussion> existingByGitlabId,
                              Set<Long> existingNoteIds) {
        List<MergeRequestNote> toSave = new ArrayList<>();
        for (GitLabDiscussionDto dto : discussions) {
            if (dto.notes() == null) {
                continue;
            }
            MergeRequestDiscussion discussion = existingByGitlabId.get(dto.id());
            if (discussion == null) {
                continue;
            }
            collectNewNotes(dto.notes(), mrId, discussion.getId(), existingNoteIds, toSave);
        }
        if (!toSave.isEmpty()) {
            noteRepository.saveAll(toSave);
        }
    }

    private void collectNewNotes(List<GitLabNoteDto> notes,
                                 Long mrId,
                                 Long discussionId,
                                 Set<Long> existingNoteIds,
                                 List<MergeRequestNote> toSave) {
        for (GitLabNoteDto noteDto : notes) {
            if (noteDto == null || noteDto.id() == null) {
                continue;
            }
            if (!existingNoteIds.contains(noteDto.id())) {
                toSave.add(gitLabMapper.toNote(noteDto, mrId, discussionId));
            }
        }
    }
}
