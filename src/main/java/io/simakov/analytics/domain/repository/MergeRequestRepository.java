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

    @Query("SELECT DISTINCT mr.authorGitlabUserId FROM MergeRequest mr WHERE mr.trackedProjectId = :projectId AND mr.authorGitlabUserId IS NOT NULL")
    List<Long> findDistinctAuthorIdsByTrackedProjectId(@Param("projectId") Long projectId);

    @Query("SELECT DISTINCT mr.authorGitlabUserId FROM MergeRequest mr WHERE mr.trackedProjectId IN :projectIds AND mr.authorGitlabUserId IS NOT NULL")
    List<Long> findDistinctAuthorIdsByTrackedProjectIdIn(@Param("projectIds") List<Long> projectIds);

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
     * MRs merged within the given period by specific GitLab authors across given projects.
     */
    @Query("""
        SELECT mr FROM MergeRequest mr
        WHERE mr.trackedProjectId IN :projectIds
        AND mr.authorGitlabUserId IN :authorIds
        AND mr.mergedAtGitlab >= :dateFrom
        AND mr.mergedAtGitlab <= :dateTo
        ORDER BY mr.mergedAtGitlab DESC
        """)
    List<MergeRequest> findMergedInPeriodByAuthors(@Param("projectIds") List<Long> projectIds,
                                                   @Param("authorIds") List<Long> authorIds,
                                                   @Param("dateFrom") Instant dateFrom,
                                                   @Param("dateTo") Instant dateTo);

    /**
     * MRs merged within the given period that have at least one commit authored by the given emails.
     * Used as fallback when gitlab_user_id is not resolved for a tracked user.
     */
    @Query("""
        SELECT DISTINCT mr FROM MergeRequest mr
        JOIN MergeRequestCommit c ON c.mergeRequestId = mr.id
        WHERE mr.trackedProjectId IN :projectIds
        AND LOWER(c.authorEmail) IN :authorEmails
        AND mr.mergedAtGitlab >= :dateFrom
        AND mr.mergedAtGitlab <= :dateTo
        ORDER BY mr.mergedAtGitlab DESC
        """)
    List<MergeRequest> findMergedInPeriodByCommitEmails(@Param("projectIds") List<Long> projectIds,
                                                        @Param("authorEmails") List<String> authorEmails,
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

    /**
     * Weekly lead time distribution (MR open → merge) for DORA chart.
     * Returns rows: [period_date, mr_count, median_hours, p75_hours, p95_hours]
     */
    @Query(nativeQuery = true,
           value = """
               SELECT
                 DATE_TRUNC('week', mr.merged_at_gitlab)::date              AS period,
                 COUNT(*)                                                     AS mr_count,
                 PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (mr.merged_at_gitlab - mr.created_at_gitlab)) / 3600) AS median_hours,
                 PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (mr.merged_at_gitlab - mr.created_at_gitlab)) / 3600) AS p75_hours,
                 PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (mr.merged_at_gitlab - mr.created_at_gitlab)) / 3600) AS p95_hours
               FROM merge_request mr
               WHERE mr.state = 'MERGED'
                 AND mr.tracked_project_id IN (:projectIds)
                 AND mr.merged_at_gitlab > mr.created_at_gitlab
                 AND mr.merged_at_gitlab >= :dateFrom
               GROUP BY DATE_TRUNC('week', mr.merged_at_gitlab)
               ORDER BY period
               """)
    List<Object[]> findLeadTimeByWeek(@Param("projectIds") List<Long> projectIds,
                                      @Param("dateFrom") Instant dateFrom);

    /**
     * Overall lead time summary for the selected period.
     * Returns single row: [mr_count, median_hours, p75_hours, p95_hours]
     */
    @Query(nativeQuery = true,
           value = """
               SELECT
                 COUNT(*)                                                     AS mr_count,
                 PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (mr.merged_at_gitlab - mr.created_at_gitlab)) / 3600) AS median_hours,
                 PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (mr.merged_at_gitlab - mr.created_at_gitlab)) / 3600) AS p75_hours,
                 PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (mr.merged_at_gitlab - mr.created_at_gitlab)) / 3600) AS p95_hours
               FROM merge_request mr
               WHERE mr.state = 'MERGED'
                 AND mr.tracked_project_id IN (:projectIds)
                 AND mr.merged_at_gitlab > mr.created_at_gitlab
                 AND mr.merged_at_gitlab >= :dateFrom
               """)
    List<Object[]> findLeadTimeSummary(@Param("projectIds") List<Long> projectIds,
                                       @Param("dateFrom") Instant dateFrom);
}
