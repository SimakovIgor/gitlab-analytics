package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.AiInsightRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiInsightCacheRepository extends JpaRepository<AiInsightRecord, Long> {

    Optional<AiInsightRecord> findByWorkspaceIdAndPeriodAndProjectIdsHash(
        Long workspaceId, String period, String projectIdsHash);

    void deleteByWorkspaceIdAndPeriodAndProjectIdsHash(
        Long workspaceId, String period, String projectIdsHash);
}
