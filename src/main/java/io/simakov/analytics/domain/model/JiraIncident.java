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
 * A Jira incident linked to a tracked project via component name matching.
 * One Jira issue may produce multiple rows if it references multiple components/projects.
 */
@Entity
@Table(name = "jira_incident")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JiraIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id",
            nullable = false)
    private Long workspaceId;

    @Column(name = "tracked_project_id",
            nullable = false)
    private Long trackedProjectId;

    @Column(name = "jira_key",
            nullable = false,
            length = 64)
    private String jiraKey;

    @Column(name = "summary",
            length = 1024)
    private String summary;

    @Column(name = "created_at",
            nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "component_name",
            length = 255)
    private String componentName;

    @Column(name = "impact_started_at")
    private Instant impactStartedAt;

    @Column(name = "impact_ended_at")
    private Instant impactEndedAt;

    @UpdateTimestamp
    @Column(name = "synced_at",
            nullable = false)
    private Instant syncedAt;
}
