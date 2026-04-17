package io.simakov.analytics.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabUserSearchDto(
    Long id,
    String username,
    String name,
    @JsonProperty("public_email") String publicEmail,
    @JsonProperty("avatar_url") String avatarUrl
) {

}
