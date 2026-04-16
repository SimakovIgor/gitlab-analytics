package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

}
