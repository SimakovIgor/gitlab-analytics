package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabMergeRequestDto(
    Long id,
    Long iid,
    String title,
    String description,
    String state,
    GitLabUserDto author,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("merged_at") Instant mergedAt,
    @JsonProperty("closed_at") Instant closedAt,
    @JsonProperty("merged_by") GitLabUserDto mergedBy,
    Integer additions,
    Integer deletions,
    @JsonProperty("changes_count") String changesCount,
    @JsonProperty("web_url") String webUrl
) {

}
