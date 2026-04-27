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
     * Count incidents within a bounded interval [dateFrom, dateTo).
     * Used by CompareService for period comparison.
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

    /** Whether any Jira incidents have been synced for this workspace (period-independent). */
    boolean existsByWorkspaceId(Long workspaceId);
}
