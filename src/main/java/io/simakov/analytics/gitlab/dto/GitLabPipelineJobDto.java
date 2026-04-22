package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Represents a GitLab pipeline job from GET /projects/:id/pipelines/:pipeline_id/jobs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabPipelineJobDto(
    @JsonProperty("id") Long id,
    @JsonProperty("name") String name,
    @JsonProperty("stage") String stage,
    @JsonProperty("status") String status,
    @JsonProperty("finished_at") Instant finishedAt
) {

}
