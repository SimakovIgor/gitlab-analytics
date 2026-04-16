package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequestDiscussion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MergeRequestDiscussionRepository extends JpaRepository<MergeRequestDiscussion, Long> {

    Optional<MergeRequestDiscussion> findByMergeRequestIdAndGitlabDiscussionId(Long mergeRequestId,
                                                                               String gitlabDiscussionId);
}
