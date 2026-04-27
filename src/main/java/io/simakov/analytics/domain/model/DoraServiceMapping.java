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

/**
 * Maps an external source identifier to a {@link DoraService}.
 * Example: sourceType=GITLAB, sourceKey="12345" (project id)
 *          sourceType=JIRA,   sourceKey="payments-service" (component name)
 */
@Entity
@Table(name = "dora_service_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoraServiceMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dora_service_id", nullable = false)
    private Long doraServiceId;

    /** Source system type (GITLAB / JIRA / PAGERDUTY / etc.). */
    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    /** External identifier within the source system. */
    @Column(name = "source_key", nullable = false, length = 512)
    private String sourceKey;
}
