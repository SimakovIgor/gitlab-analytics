package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a GitLab pipeline from GET /projects/:id/pipelines.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabPipelineDto(
    @JsonProperty("id") Long id,
    @JsonProperty("ref") String ref,
    @JsonProperty("status") String status,
    @JsonProperty("sha") String sha
) {
}
