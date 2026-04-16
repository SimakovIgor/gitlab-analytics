package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabCommitDto(
    String id,
    String title,
    @JsonProperty("author_name") String authorName,
    @JsonProperty("author_email") String authorEmail,
    @JsonProperty("authored_date") Instant authoredDate,
    @JsonProperty("committed_date") Instant committedDate
) {

}
