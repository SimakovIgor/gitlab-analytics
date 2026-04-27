package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.DoraIncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DoraIncidentEventRepository extends JpaRepository<DoraIncidentEvent, Long> {

    Optional<DoraIncidentEvent> findByWorkspaceIdAndIdempotencyKey(Long workspaceId,
                                                                    String idempotencyKey);

    long countByWorkspaceIdAndDoraServiceIdIsNull(Long workspaceId);

    long countByWorkspaceId(Long workspaceId);

    /**
     * Count all incidents for the given services in the period (for CFR).
     */
    @Query("""
        SELECT COUNT(e) FROM DoraIncidentEvent e
        WHERE e.workspaceId = :workspaceId
          AND e.doraServiceId IN :serviceIds
          AND e.startedAt >= :dateFrom
        """)
    long countIncidents(Long workspaceId,
                        List<Long> serviceIds,
                        Instant dateFrom);

    /**
     * Count incidents in a bounded interval [dateFrom, dateTo).
     */
    @Query("""
        SELECT COUNT(e) FROM DoraIncidentEvent e
        WHERE e.workspaceId = :workspaceId
          AND e.doraServiceId IN :serviceIds
          AND e.startedAt >= :dateFrom
          AND e.startedAt < :dateTo
        """)
    long countIncidentsBetween(Long workspaceId,
                               List<Long> serviceIds,
                               Instant dateFrom,
                               Instant dateTo);

    /**
     * Count resolved incidents (with resolvedAt) — used for MTTR denominator.
     */
    @Query("""
        SELECT COUNT(e) FROM DoraIncidentEvent e
        WHERE e.workspaceId = :workspaceId
          AND e.doraServiceId IN :serviceIds
          AND e.resolvedAt IS NOT NULL
          AND e.resolvedAt > e.startedAt
          AND e.startedAt >= :dateFrom
        """)
    long countResolvedIncidents(Long workspaceId,
                                List<Long> serviceIds,
                                Instant dateFrom);

    /**
     * Average MTTR (hours) for resolved incidents (resolvedAt - startedAt).
     * Only incidents where resolvedAt > startedAt are included.
     */
    @Query(value = """
        SELECT AVG(EXTRACT(EPOCH FROM (e.resolved_at - e.started_at)) / 3600.0)
        FROM dora_incident_event e
        WHERE e.workspace_id = :workspaceId
          AND e.dora_service_id IN :serviceIds
          AND e.resolved_at IS NOT NULL
          AND e.resolved_at > e.started_at
          AND e.started_at >= :dateFrom
        """,
           nativeQuery = true)
    Double findAvgMttrHours(Long workspaceId,
                            List<Long> serviceIds,
                            Instant dateFrom);

    /**
     * Weekly incident counts for the CFR chart.
     */
    @Query(value = """
        SELECT TO_CHAR(DATE_TRUNC('week', e.started_at), 'IYYY-"W"IW') AS weekLabel,
               COUNT(*)                                                  AS incidentCount
        FROM dora_incident_event e
        WHERE e.workspace_id = :workspaceId
          AND e.dora_service_id IN :serviceIds
          AND e.started_at >= :dateFrom
        GROUP BY DATE_TRUNC('week', e.started_at)
        ORDER BY DATE_TRUNC('week', e.started_at)
        """,
           nativeQuery = true)
    List<IncidentWeekProjection> countIncidentsByWeek(Long workspaceId,
                                                      List<Long> serviceIds,
                                                      Instant dateFrom);

    /**
     * Individual resolved incidents for the MTTR bar chart.
     */
    @Query(value = """
        SELECT TO_CHAR(DATE_TRUNC('week', e.started_at), 'IYYY-"W"IW')        AS weekLabel,
               COALESCE(e.external_id, CAST(e.id AS TEXT))                     AS jiraKey,
               e.started_at                                                    AS impactStartedAt,
               EXTRACT(EPOCH FROM (e.resolved_at - e.started_at)) / 3600.0    AS durationHours
        FROM dora_incident_event e
        WHERE e.workspace_id = :workspaceId
          AND e.dora_service_id IN :serviceIds
          AND e.resolved_at IS NOT NULL
          AND e.resolved_at > e.started_at
          AND e.started_at >= :dateFrom
        ORDER BY e.started_at
        """,
           nativeQuery = true)
    List<MttrIncidentProjection> findMttrIncidents(Long workspaceId,
                                                   List<Long> serviceIds,
                                                   Instant dateFrom);
}
