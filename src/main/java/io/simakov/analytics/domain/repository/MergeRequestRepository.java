package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MergeRequestRepository extends JpaRepository<MergeRequest, Long> {

    Optional<MergeRequest> findByTrackedProjectIdAndGitlabMrId(Long trackedProjectId,
                                                               Long gitlabMrId);

    /**
     * MRs created within the given period for the given projects
     */
    @Query("""
        SELECT mr FROM MergeRequest mr
        WHERE mr.trackedProjectId IN :projectIds
        AND mr.createdAtGitlab >= :dateFrom
        AND mr.createdAtGitlab <= :dateTo
        """)
    List<MergeRequest> findCreatedInPeriod(@Param("projectIds") List<Long> projectIds,
                                           @Param("dateFrom") Instant dateFrom,
                                           @Param("dateTo") Instant dateTo);

    /**
     * MRs merged within the given period for the given projects
     */
    @Query("""
        SELECT mr FROM MergeRequest mr
        WHERE mr.trackedProjectId IN :projectIds
        AND mr.mergedAtGitlab >= :dateFrom
        AND mr.mergedAtGitlab <= :dateTo
        """)
    List<MergeRequest> findMergedInPeriod(@Param("projectIds") List<Long> projectIds,
                                          @Param("dateFrom") Instant dateFrom,
                                          @Param("dateTo") Instant dateTo);

    /**
     * Finds the GitLab user ID of MR authors who have commits with the given email.
     * Used to populate gitlabUserId in TrackedUserAlias when adding users via email-based discovery.
     */
    @Query("""
        SELECT DISTINCT mr.authorGitlabUserId FROM MergeRequest mr
        JOIN MergeRequestCommit c ON c.mergeRequestId = mr.id
        WHERE LOWER(c.authorEmail) = LOWER(:email)
        AND mr.authorGitlabUserId IS NOT NULL
        """)
    List<Long> findAuthorGitlabUserIdByCommitEmail(@Param("email") String email);
}
