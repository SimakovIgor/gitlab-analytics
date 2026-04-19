package io.simakov.analytics.domain.model;

import io.simakov.analytics.domain.model.enums.PeriodType;
import io.simakov.analytics.domain.model.enums.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "metric_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "tracked_user_id")
    private Long trackedUserId;

    @Column(name = "tracked_project_id")
    private Long trackedProjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type",
            nullable = false,
            length = 50)
    private PeriodType periodType;

    @Column(name = "date_from",
            nullable = false)
    private Instant dateFrom;

    @Column(name = "date_to",
            nullable = false)
    private Instant dateTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type",
            nullable = false,
            length = 50)
    private ScopeType scopeType;

    @Column(name = "snapshot_date",
            nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "window_days",
            nullable = false)
    private int windowDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_json",
            nullable = false,
            columnDefinition = "jsonb")
    private String metricsJson;

    @CreationTimestamp
    @Column(name = "created_at",
            nullable = false,
            updatable = false)
    private Instant createdAt;
}
