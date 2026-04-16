package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.NotNull;

public record EnableProjectRequest(
    @NotNull Boolean enabled
) {

}
