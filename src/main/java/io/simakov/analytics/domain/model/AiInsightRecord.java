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

import java.time.Instant;

@Entity
@Table(name = "ai_insight_cache")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInsightRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workspaceId;

    @Column(nullable = false,
            length = 32)
    private String period;

    @Column(nullable = false,
            length = 64)
    private String projectIdsHash;

    @Column(nullable = false,
            columnDefinition = "TEXT")
    private String insightsJson;

    @Column(nullable = false)
    private int tokensUsed;

    @Column(nullable = false)
    private Instant generatedAt;
}
