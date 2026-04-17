package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitLabProjectDto(
    Long id,
    String name,
    @JsonProperty("path_with_namespace") String pathWithNamespace,
    @JsonProperty("web_url") String webUrl
) {

}
