package io.simakov.analytics.domain.model;

import io.simakov.analytics.domain.model.enums.SyncStatus;
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

import java.time.Instant;

@Entity
@Table(name = "sync_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,
            length = 50)
    private SyncStatus status;

    @CreationTimestamp
    @Column(name = "started_at",
            nullable = false,
            updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "date_from")
    private Instant dateFrom;

    @Column(name = "date_to")
    private Instant dateTo;

    /**
     * Full sync request serialized as JSON for auditability
     */
    @Column(name = "payload_json",
            columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "error_message",
            columnDefinition = "TEXT")
    private String errorMessage;
}
