package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.JiraIncident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JiraIncidentRepository extends JpaRepository<JiraIncident, Long> {

    Optional<JiraIncident> findByJiraKeyAndTrackedProjectId(String jiraKey,
                                                            Long trackedProjectId);

    /**
     * Count incidents for the given projects created within the period.
     */
    @Query("""
        SELECT COUNT(ji) FROM JiraIncident ji
        WHERE ji.trackedProjectId IN :projectIds
          AND ji.createdAt >= :dateFrom
        """)
    long countIncidentsInPeriod(List<Long> projectIds,
                                Instant dateFrom);

    /**
     * Count incidents within a bounded interval [dateFrom, dateTo).
     */
    @Query("""
        SELECT COUNT(ji) FROM JiraIncident ji
        WHERE ji.trackedProjectId IN :projectIds
          AND ji.createdAt >= :dateFrom
          AND ji.createdAt < :dateTo
        """)
    long countIncidentsInPeriodBetween(List<Long> projectIds,
                                       Instant dateFrom,
                                       Instant dateTo);

    /**
     * Weekly incident counts for the CFR chart.
     */
    @Query(value = """
        SELECT TO_CHAR(DATE_TRUNC('week', ji.created_at), 'IYYY-"W"IW') AS weekLabel,
               COUNT(*)                                                   AS incidentCount
        FROM jira_incident ji
        WHERE ji.tracked_project_id IN :projectIds
          AND ji.created_at >= :dateFrom
        GROUP BY DATE_TRUNC('week', ji.created_at)
        ORDER BY DATE_TRUNC('week', ji.created_at)
        """,
           nativeQuery = true)
    List<IncidentWeekProjection> countIncidentsByWeek(List<Long> projectIds,
                                                      Instant dateFrom);

    List<JiraIncident> findAllByWorkspaceId(Long workspaceId);
}
