package io.simakov.analytics.gitlab.mapper;

import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestApproval;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.MergeRequestDiscussion;
import io.simakov.analytics.domain.model.MergeRequestNote;
import io.simakov.analytics.domain.model.enums.MrState;
import io.simakov.analytics.gitlab.dto.GitLabCommitDto;
import io.simakov.analytics.gitlab.dto.GitLabDiscussionDto;
import io.simakov.analytics.gitlab.dto.GitLabMergeRequestDto;
import io.simakov.analytics.gitlab.dto.GitLabNoteDto;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class GitLabMapper {

    public MergeRequest toMergeRequest(GitLabMergeRequestDto dto,
                                       Long trackedProjectId) {
        MergeRequest mr = new MergeRequest();
        mr.setTrackedProjectId(trackedProjectId);
        mr.setGitlabMrId(dto.id());
        mr.setGitlabMrIid(dto.iid());
        mr.setTitle(dto.title());
        mr.setDescription(dto.description());
        mr.setState(parseMrState(dto.state()));
        mr.setWebUrl(dto.webUrl());
        mr.setCreatedAtGitlab(dto.createdAt());
        mr.setUpdatedAtGitlab(dto.updatedAt());
        mr.setMergedAtGitlab(dto.mergedAt());
        mr.setClosedAtGitlab(dto.closedAt());

        if (dto.author() != null) {
            mr.setAuthorGitlabUserId(dto.author().id());
            mr.setAuthorUsername(dto.author().username());
            mr.setAuthorName(dto.author().name());
        }

        if (dto.mergedBy() != null) {
            mr.setMergedByGitlabUserId(dto.mergedBy().id());
        }

        mr.setAdditions(nullToZero(dto.additions()));
        mr.setDeletions(nullToZero(dto.deletions()));

        int changes = parseChangesCount(dto.changesCount());
        mr.setChangesCount(changes);

        return mr;
    }

    public void updateMergeRequest(MergeRequest existing,
                                   GitLabMergeRequestDto dto) {
        existing.setState(parseMrState(dto.state()));
        existing.setTitle(dto.title());
        existing.setMergedAtGitlab(dto.mergedAt());
        existing.setClosedAtGitlab(dto.closedAt());
        existing.setUpdatedAtGitlab(dto.updatedAt());
        existing.setAdditions(nullToZero(dto.additions()));
        existing.setDeletions(nullToZero(dto.deletions()));
        existing.setChangesCount(parseChangesCount(dto.changesCount()));

        if (dto.mergedBy() != null) {
            existing.setMergedByGitlabUserId(dto.mergedBy().id());
        }
    }

    public MergeRequestCommit toCommit(GitLabCommitDto dto,
                                       Long mergeRequestId) {
        int additions = dto.stats() != null
            ? dto.stats().additions()
            : 0;
        int deletions = dto.stats() != null
            ? dto.stats().deletions()
            : 0;
        return MergeRequestCommit.builder()
            .mergeRequestId(mergeRequestId)
            .gitlabCommitSha(dto.id())
            .authorName(dto.authorName())
            .authorEmail(dto.authorEmail())
            .authoredDate(dto.authoredDate())
            .committedDate(dto.committedDate())
            .additions(additions)
            .deletions(deletions)
            .totalChanges(additions + deletions)
            .mergeCommit(dto.isMergeCommit())
            .build();
    }

    public MergeRequestDiscussion toDiscussion(GitLabDiscussionDto dto,
                                               Long mergeRequestId) {
        return MergeRequestDiscussion.builder()
            .mergeRequestId(mergeRequestId)
            .gitlabDiscussionId(dto.id())
            .individualNote(dto.individualNote())
            .createdAtGitlab(dto.notes() != null && !dto.notes().isEmpty()
                ? dto.notes().getFirst().createdAt()
                : null)
            .build();
    }

    public MergeRequestNote toNote(GitLabNoteDto dto,
                                   Long mergeRequestId,
                                   Long discussionId) {
        MergeRequestNote note = new MergeRequestNote();
        note.setMergeRequestId(mergeRequestId);
        note.setDiscussionId(discussionId);
        note.setGitlabNoteId(dto.id());
        note.setBody(dto.body());
        note.setSystem(dto.system());
        note.setInternal(dto.internal());
        note.setCreatedAtGitlab(dto.createdAt());
        note.setUpdatedAtGitlab(dto.updatedAt());

        if (dto.author() != null) {
            note.setAuthorGitlabUserId(dto.author().id());
            note.setAuthorUsername(dto.author().username());
            note.setAuthorName(dto.author().name());
        }

        return note;
    }

    public MergeRequestApproval toApproval(GitLabUserDto user,
                                           Long mergeRequestId) {
        return MergeRequestApproval.builder()
            .mergeRequestId(mergeRequestId)
            .approvedByGitlabUserId(user.id())
            .approvedByUsername(user.username())
            .approvedByName(user.name())
            .build();
    }

    private MrState parseMrState(String state) {
        if (state == null) {
            return MrState.OPENED;
        }
        return switch (state.toLowerCase(Locale.getDefault())) {
            case "merged" -> MrState.MERGED;
            case "closed" -> MrState.CLOSED;
            case "locked" -> MrState.LOCKED;
            default -> MrState.OPENED;
        };
    }

    private int parseChangesCount(String changesCount) {
        if (changesCount == null || changesCount.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(changesCount);
        } catch (NumberFormatException e) {
            // GitLab may return "100+" for large MRs
            return 100;
        }
    }

    private int nullToZero(Integer value) {
        return value == null
            ? 0
            : value;
    }
}
