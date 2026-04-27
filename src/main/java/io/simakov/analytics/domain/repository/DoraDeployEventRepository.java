package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.DoraDeployEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DoraDeployEventRepository extends JpaRepository<DoraDeployEvent, Long> {

    Optional<DoraDeployEvent> findByWorkspaceIdAndIdempotencyKey(Long workspaceId,
                                                                  String idempotencyKey);

    Optional<DoraDeployEvent> findByWorkspaceIdAndDoraServiceIdAndExternalId(Long workspaceId,
                                                                              Long doraServiceId,
                                                                              String externalId);

    long countByWorkspaceIdAndDoraServiceIdIsNull(Long workspaceId);

    long countByWorkspaceId(Long workspaceId);

    /**
     * Count successful deployments for deploy frequency calculation.
     */
    @Query("""
        SELECT COUNT(e) FROM DoraDeployEvent e
        WHERE e.workspaceId = :workspaceId
          AND e.doraServiceId IN :serviceIds
          AND e.status = io.simakov.analytics.dora.model.DeployStatus.SUCCESS
          AND e.deployedAt >= :dateFrom
        """)
    long countSuccessfulDeploys(Long workspaceId,
                                List<Long> serviceIds,
                                Instant dateFrom);

    /**
     * Count successful deployments within a bounded interval [dateFrom, dateTo).
     * Used for previous-period comparison in health score and insights.
     */
    @Query("""
        SELECT COUNT(e) FROM DoraDeployEvent e
        WHERE e.workspaceId = :workspaceId
          AND e.doraServiceId IN :serviceIds
          AND e.status = io.simakov.analytics.dora.model.DeployStatus.SUCCESS
          AND e.deployedAt >= :dateFrom
          AND e.deployedAt < :dateTo
        """)
    long countSuccessfulDeploysBetween(Long workspaceId,
                                       List<Long> serviceIds,
                                       Instant dateFrom,
                                       Instant dateTo);

    /**
     * Weekly successful deployment counts for the deploy frequency chart.
     */
    @Query(value = """
        SELECT TO_CHAR(DATE_TRUNC('week', e.deployed_at), 'IYYY-"W"IW') AS weekLabel,
               COUNT(*)                                                  AS deployCount
        FROM dora_deploy_event e
        WHERE e.workspace_id = :workspaceId
          AND e.dora_service_id IN :serviceIds
          AND e.status = 'SUCCESS'
          AND e.deployed_at >= :dateFrom
        GROUP BY DATE_TRUNC('week', e.deployed_at)
        ORDER BY DATE_TRUNC('week', e.deployed_at)
        """,
           nativeQuery = true)
    List<DeployFrequencyWeekProjection> countDeploysByWeek(Long workspaceId,
                                                           List<Long> serviceIds,
                                                           Instant dateFrom);
}
