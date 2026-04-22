package io.simakov.analytics.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A GitLab release tag for a tracked project.
 * Captures when the release was created, when it was deployed to stage and prod
 * (detected via prod::deploy::* pipeline jobs).
 */
@Entity
@Table(name = "release_tag")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReleaseTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_project_id",
            nullable = false)
    private Long trackedProjectId;

    @Column(name = "tag_name",
            nullable = false,
            length = 255)
    private String tagName;

    /**
     * When the tag / release was created in GitLab.
     */
    @Column(name = "tag_created_at",
            nullable = false)
    private Instant tagCreatedAt;

    /**
     * released_at from GitLab Releases API (may differ from tag creation).
     */
    @Column(name = "released_at")
    private Instant releasedAt;

    /**
     * Pipeline ID associated with this tag (from releases API or tag pipeline).
     */
    @Column(name = "pipeline_id")
    private Long pipelineId;

    /**
     * Earliest finished_at of a successful stage deploy job for this release.
     */
    @Column(name = "stage_deployed_at")
    private Instant stageDeployedAt;

    /**
     * Earliest finished_at of a successful prod::deploy::* job for this release.
     */
    @Column(name = "prod_deployed_at")
    private Instant prodDeployedAt;

    @UpdateTimestamp
    @Column(name = "synced_at",
            nullable = false)
    private Instant syncedAt;
}
