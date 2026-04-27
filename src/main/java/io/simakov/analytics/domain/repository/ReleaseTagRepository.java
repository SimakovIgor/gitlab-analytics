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
     * Prod deploy count within a bounded interval [dateFrom, dateTo).
     * Used by CompareService and DigestService for period comparison.
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
}
