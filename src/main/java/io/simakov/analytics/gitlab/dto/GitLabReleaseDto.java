package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Represents a GitLab Release from GET /projects/:id/releases.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabReleaseDto(
    @JsonProperty("tag_name") String tagName,
    @JsonProperty("released_at") Instant releasedAt,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("commit") GitLabReleaseCommitDto commit
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitLabReleaseCommitDto(
        @JsonProperty("id") String id,
        @JsonProperty("created_at") Instant createdAt
    ) {

    }
}
