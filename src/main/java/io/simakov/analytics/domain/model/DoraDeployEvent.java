package io.simakov.analytics.domain.model;

import io.simakov.analytics.dora.model.DeploySource;
import io.simakov.analytics.dora.model.DeployStatus;
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
 * Normalized deployment event in the universal DORA model.
 * Written by source adapters (GitLab, GitHub Actions, Jenkins) and Manual API.
 * doraServiceId is nullable — events without a matching service are stored for diagnostics.
 */
@Entity
@Table(name = "dora_deploy_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoraDeployEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** Null when the service name from the request could not be resolved. */
    @Column(name = "dora_service_id")
    private Long doraServiceId;

    @Column(name = "environment", nullable = false, length = 128)
    private String environment;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeployStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 64)
    private DeploySource source;

    @Column(name = "version", length = 255)
    private String version;

    @Column(name = "commit_sha", length = 128)
    private String commitSha;

    /** Start of the commit range this deploy covers (for Lead Time calculation). */
    @Column(name = "commit_range_from", length = 128)
    private String commitRangeFrom;

    /** End of the commit range this deploy covers (for Lead Time calculation). */
    @Column(name = "commit_range_to", length = 128)
    private String commitRangeTo;

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
