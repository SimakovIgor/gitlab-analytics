package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabMrDiffFileDto(
    @JsonProperty("new_path") String newPath,
    String diff,
    @JsonProperty("too_large") boolean tooLarge
) {

}
