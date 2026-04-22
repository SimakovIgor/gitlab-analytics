package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.ReleaseTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReleaseTagRepository extends JpaRepository<ReleaseTag, Long> {

    Optional<ReleaseTag> findByTrackedProjectIdAndTagName(Long trackedProjectId,
                                                          String tagName);

    List<ReleaseTag> findAllByTrackedProjectIdOrderByTagCreatedAtDesc(Long trackedProjectId);

    @Query("""
        SELECT r FROM ReleaseTag r
        WHERE r.trackedProjectId IN :projectIds
        ORDER BY r.tagCreatedAt DESC
        """)
    List<ReleaseTag> findAllByProjectIdsOrderByCreatedAtDesc(List<Long> projectIds);

    /**
     * Count prod deployments in the given period for deploy frequency calculation.
     */
    @Query("""
        SELECT COUNT(r) FROM ReleaseTag r
        WHERE r.trackedProjectId IN :projectIds
          AND r.prodDeployedAt IS NOT NULL
          AND r.prodDeployedAt >= :dateFrom
        """)
    long countProdDeploysInPeriod(List<Long> projectIds,
                                  Instant dateFrom);

    /**
     * Prod deploy count within a bounded interval [dateFrom, dateTo).
     * Used by DORA insights for previous-period comparison.
     */
    @Query("""
        SELECT COUNT(r) FROM ReleaseTag r
        WHERE r.trackedProjectId IN :projectIds
          AND r.prodDeployedAt IS NOT NULL
          AND r.prodDeployedAt >= :dateFrom
          AND r.prodDeployedAt < :dateTo
        """)
    long countProdDeploysInPeriodBetween(List<Long> projectIds,
                                         Instant dateFrom,
                                         Instant dateTo);

    /**
     * Weekly prod deployment counts for the deploy frequency chart.
     */
    @Query(value = """
        SELECT TO_CHAR(DATE_TRUNC('week', rt.prod_deployed_at), 'IYYY-"W"IW') AS weekLabel,
               COUNT(*)                                                        AS deployCount
        FROM release_tag rt
        WHERE rt.tracked_project_id IN :projectIds
          AND rt.prod_deployed_at IS NOT NULL
          AND rt.prod_deployed_at >= :dateFrom
        GROUP BY DATE_TRUNC('week', rt.prod_deployed_at)
        ORDER BY DATE_TRUNC('week', rt.prod_deployed_at)
        """,
           nativeQuery = true)
    List<DeployFrequencyWeekProjection> countProdDeploysByWeek(List<Long> projectIds,
                                                               Instant dateFrom);
}
