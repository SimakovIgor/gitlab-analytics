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
     * Finds the most likely GitLab user ID for a given commit email.
     * Groups by MR author and returns the one with the most commits from that email —
     * this is the person who both opened the MR and wrote the commits.
     */
    @Query("""
        SELECT mr.authorGitlabUserId FROM MergeRequest mr
        JOIN MergeRequestCommit c ON c.mergeRequestId = mr.id
        WHERE LOWER(c.authorEmail) = LOWER(:email)
        AND mr.authorGitlabUserId IS NOT NULL
        GROUP BY mr.authorGitlabUserId
        ORDER BY COUNT(c.id) DESC
        """)
    List<Long> findAuthorGitlabUserIdByCommitEmail(@Param("email") String email);
}
