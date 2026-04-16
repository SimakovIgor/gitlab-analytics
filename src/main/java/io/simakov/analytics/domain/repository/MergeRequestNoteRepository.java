package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequestNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MergeRequestNoteRepository extends JpaRepository<MergeRequestNote, Long> {

    Optional<MergeRequestNote> findByMergeRequestIdAndGitlabNoteId(Long mergeRequestId,
                                                                   Long gitlabNoteId);

    List<MergeRequestNote> findByMergeRequestIdIn(List<Long> mergeRequestIds);
}
