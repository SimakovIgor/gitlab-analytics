package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MergeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MergeRequestRepository extends JpaRepository<MergeRequest, Long> {

    Optional<MergeRequest> findByTrackedProjectIdAndGitlabMrId(Long trackedProjectId,
                                                               Long gitlabMrId);

    @Query("SELECT DISTINCT mr.authorGitlabUserId FROM MergeRequest mr WHERE mr.trackedProjectId = :projectId AND mr.authorGitlabUserId IS NOT NULL")
    List<Long> findDistinctAuthorIdsByTrackedProjectId(@Param("projectId") Long projectId);

    /**
     * Returns distinct MR authors for a set of projects.
     * Used for auto-discovery of team members after Phase 1 (FAST) sync completes.
     */
    @Query("""
        SELECT DISTINCT mr.authorGitlabUserId AS authorGitlabUserId,
                        mr.authorName         AS authorName,
                        mr.authorUsername     AS authorUsername
        FROM MergeRequest mr
        WHERE mr.trackedProjectId IN :projectIds
        AND mr.authorGitlabUserId IS NOT NULL
        AND mr.authorUsername IS NOT NULL
        """)
    List<MrAuthorProjection> findDistinctAuthorsByProjectIds(@Param("projectIds") List<Long> projectIds);

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
     * MR count per tracked user for the given workspace.
     * Joins through tracked_user_alias to resolve gitlab_user_id → tracked_user_id.
     */
    @Query(nativeQuery = true,
           value = """
               SELECT ta.tracked_user_id AS tracked_user_id,
                      COUNT(mr.id)       AS mr_count
               FROM tracked_user_alias ta
               JOIN merge_request mr ON mr.author_gitlab_user_id = ta.gitlab_user_id
               JOIN tracked_user tu  ON tu.id = ta.tracked_user_id
               WHERE ta.gitlab_user_id IS NOT NULL
                 AND tu.workspace_id = :workspaceId
               GROUP BY ta.tracked_user_id
               """)
    List<UserMrCountProjection> countMrsByTrackedUser(@Param("workspaceId") Long workspaceId);

    /**
     * All currently open MRs for the given projects.
     * Used by insight rules to detect stuck or long-open MRs.
     */
    @Query("""
        SELECT mr FROM MergeRequest mr
        WHERE mr.trackedProjectId IN :projectIds
        AND mr.state = :state
        """)
    List<MergeRequest> findOpenByProjectIds(@Param("projectIds") List<Long> projectIds,
                                            @Param("state") io.simakov.analytics.domain.model.enums.MrState state);

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
    List<LeadTimeWeekProjection> findLeadTimeByWeek(@Param("projectIds") List<Long> projectIds,
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
    List<LeadTimeSummaryProjection> findLeadTimeSummary(@Param("projectIds") List<Long> projectIds,
                                                        @Param("dateFrom") Instant dateFrom);

    // ── Реальный Lead Time for Changes (mr.created_at → release_tag.prod_deployed_at) ──

    /**
     * Реальный Lead Time for Changes — еженедельная разбивка по дате деплоя в прод.
     * Учитывает только MR, для которых известна дата prod_deployed_at.
     * Период задаётся по дате деплоя (dateFrom ≤ rt.prod_deployed_at).
     */
    @Query(nativeQuery = true,
           value = """
               SELECT
                 DATE_TRUNC('week', rt.prod_deployed_at)::date AS period,
                 COUNT(mr.id)                                   AS mr_count,
                 PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS median_days,
                 PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS p75_days,
                 PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS p95_days
               FROM merge_request mr
               JOIN release_tag rt ON rt.id = mr.release_tag_id
               WHERE mr.tracked_project_id IN (:projectIds)
                 AND rt.prod_deployed_at IS NOT NULL
                 AND rt.prod_deployed_at >= :dateFrom
               GROUP BY DATE_TRUNC('week', rt.prod_deployed_at)
               ORDER BY period
               """)
    List<RealLeadTimeWeekProjection> findRealLeadTimeByWeek(@Param("projectIds") List<Long> projectIds,
                                                            @Param("dateFrom") Instant dateFrom);

    /**
     * Реальный Lead Time for Changes — суммарная строка за период.
     * Учитывает только MR, для которых известна дата prod_deployed_at.
     */
    @Query(nativeQuery = true,
           value = """
               SELECT
                 COUNT(mr.id)                                   AS mr_count,
                 PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS median_days,
                 PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS p75_days,
                 PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS p95_days
               FROM merge_request mr
               JOIN release_tag rt ON rt.id = mr.release_tag_id
               WHERE mr.tracked_project_id IN (:projectIds)
                 AND rt.prod_deployed_at IS NOT NULL
                 AND rt.prod_deployed_at >= :dateFrom
               """)
    List<RealLeadTimeSummaryProjection> findRealLeadTimeSummary(@Param("projectIds") List<Long> projectIds,
                                                                @Param("dateFrom") Instant dateFrom);

    /**
     * Реальный Lead Time for Changes — суммарная строка за конкретный интервал [dateFrom, dateTo).
     * Используется для расчёта DORA-инсайтов по предыдущему периоду.
     */
    @Query(nativeQuery = true,
           value = """
               SELECT
                 COUNT(mr.id)                                   AS mr_count,
                 PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS median_days,
                 PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS p75_days,
                 PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY
                   EXTRACT(EPOCH FROM (rt.prod_deployed_at - mr.created_at_gitlab)) / 86400) AS p95_days
               FROM merge_request mr
               JOIN release_tag rt ON rt.id = mr.release_tag_id
               WHERE mr.tracked_project_id IN (:projectIds)
                 AND rt.prod_deployed_at IS NOT NULL
                 AND rt.prod_deployed_at >= :dateFrom
                 AND rt.prod_deployed_at < :dateTo
               """)
    List<RealLeadTimeSummaryProjection> findRealLeadTimeSummaryBetween(
        @Param("projectIds") List<Long> projectIds,
        @Param("dateFrom") Instant dateFrom,
        @Param("dateTo") Instant dateTo);

    /**
     * Сбрасывает release_tag_id у всех MR проекта перед переатрибуцией.
     * Вызывается из ReleaseSyncService перед полным переприсвоением.
     */
    @Modifying
    @Transactional
    @Query("UPDATE MergeRequest mr SET mr.releaseTagId = null WHERE mr.trackedProjectId = :projectId")
    void clearReleaseTagId(@Param("projectId") Long projectId);
}
