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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "workspace_invite")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false,
            unique = true)
    private String token;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    private Long usedByAppUserId;

    @CreationTimestamp
    @Column(nullable = false,
            updatable = false)
    private Instant createdAt;
}
