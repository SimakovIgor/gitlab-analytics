package io.simakov.analytics.domain.repository;

import io.simakov.analytics.domain.model.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    List<MetricSnapshot> findByWorkspaceIdAndSnapshotDateAndTrackedUserIdIn(Long workspaceId,
                                                                            LocalDate snapshotDate,
                                                                            List<Long> userIds);

    @Query("""
        SELECT s FROM MetricSnapshot s
        WHERE s.trackedUserId IN :userIds
        AND s.snapshotDate >= :from
        AND s.snapshotDate <= :to
        ORDER BY s.snapshotDate ASC
        """)
    List<MetricSnapshot> findHistory(@Param("userIds") List<Long> userIds,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Query("""
        SELECT s FROM MetricSnapshot s
        WHERE s.workspaceId = :workspaceId
        AND s.trackedUserId IN :userIds
        AND s.snapshotDate >= :from
        AND s.snapshotDate <= :to
        ORDER BY s.snapshotDate ASC
        """)
    List<MetricSnapshot> findHistoryByWorkspace(@Param("workspaceId") Long workspaceId,
                                                @Param("userIds") List<Long> userIds,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}
