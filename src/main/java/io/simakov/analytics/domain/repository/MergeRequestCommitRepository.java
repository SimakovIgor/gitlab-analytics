package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequestCommit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MergeRequestCommitRepository extends JpaRepository<MergeRequestCommit, Long> {

    Optional<MergeRequestCommit> findByMergeRequestIdAndGitlabCommitSha(Long mergeRequestId,
                                                                        String sha);

    List<MergeRequestCommit> findByMergeRequestIdIn(List<Long> mergeRequestIds);
}
