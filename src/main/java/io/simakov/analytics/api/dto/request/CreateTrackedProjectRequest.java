package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTrackedProjectRequest(
    @NotNull Long gitSourceId,
    @NotNull Long gitlabProjectId,
    @NotBlank String pathWithNamespace,
    @NotBlank String name,
    @NotBlank String token
) {

}
