package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequestApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MergeRequestApprovalRepository extends JpaRepository<MergeRequestApproval, Long> {

    Optional<MergeRequestApproval> findByMergeRequestIdAndApprovedByGitlabUserId(Long mergeRequestId,
                                                                                 Long userId);

    List<MergeRequestApproval> findByMergeRequestIdIn(List<Long> mergeRequestIds);
}
