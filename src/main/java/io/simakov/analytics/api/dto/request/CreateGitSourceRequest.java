package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateGitSourceRequest(
    @NotBlank String name,
    @NotBlank String baseUrl,
    @NotBlank String token
) {

}
