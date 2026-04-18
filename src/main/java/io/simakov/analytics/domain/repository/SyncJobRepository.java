package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findByStatusOrderByStartedAtDesc(SyncStatus status);

    List<SyncJob> findByStatusAndStartedAtBefore(SyncStatus status,
                                                 Instant threshold);

    List<SyncJob> findTop30ByOrderByStartedAtDesc();
}
