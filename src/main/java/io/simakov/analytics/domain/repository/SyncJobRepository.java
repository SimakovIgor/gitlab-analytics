package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.SyncJob;
import io.simakov.analytics.domain.model.enums.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    List<SyncJob> findByStatusOrderByStartedAtDesc(SyncStatus status);
}
