package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabUserDto(
    Long id,
    String username,
    String name,
    @JsonProperty("avatar_url") String avatarUrl,
    @JsonProperty("web_url") String webUrl
) {

}
