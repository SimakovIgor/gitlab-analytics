package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MetricSnapshot;
import io.simakov.analytics.domain.model.enums.ReportMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    Optional<MetricSnapshot> findByTrackedUserIdAndSnapshotDateAndReportMode(
        Long trackedUserId, LocalDate snapshotDate, ReportMode reportMode);

    @Query("SELECT s FROM MetricSnapshot s "
        + "WHERE s.trackedUserId IN :userIds "
        + "AND s.snapshotDate >= :from "
        + "AND s.snapshotDate <= :to "
        + "AND s.reportMode = :reportMode "
        + "ORDER BY s.snapshotDate ASC")
    List<MetricSnapshot> findHistory(@Param("userIds") List<Long> userIds,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("reportMode") ReportMode reportMode);
}
