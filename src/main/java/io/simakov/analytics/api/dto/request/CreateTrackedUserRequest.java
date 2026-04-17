package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateTrackedUserRequest(
    @NotBlank String displayName,
    @Email String email
) {

}
