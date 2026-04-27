package io.simakov.analytics.domain.model;

import io.simakov.analytics.dora.model.IncidentSource;
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

/**
 * Normalized incident event in the universal DORA model.
 * Written by source adapters (Jira, PagerDuty, Sentry) and Manual API.
 * doraServiceId is nullable — events without a matching service are stored for diagnostics.
 * resolvedAt is nullable — open/unresolved incidents are excluded from MTTR calculation.
 */
@Entity
@Table(name = "dora_incident_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoraIncidentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** Null when the service name from the request could not be resolved. */
    @Column(name = "dora_service_id")
    private Long doraServiceId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null for open/unresolved incidents. Such incidents are excluded from MTTR. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "severity", length = 32)
    private String severity;

    @Column(name = "status", length = 32)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 64)
    private IncidentSource source;

    /** Optional link to the deployment that caused this incident (for precise CFR). */
    @Column(name = "caused_by_deploy_id")
    private Long causedByDeployId;

    /** Client-supplied deduplication key. Unique per workspace. */
    @Column(name = "idempotency_key", length = 512)
    private String idempotencyKey;

    @Column(name = "external_id", length = 512)
    private String externalId;

    @Column(name = "external_url", length = 2048)
    private String externalUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
