package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateTrackedUserRequest(
    @Size(min = 1, max = 255) String displayName,
    @Email String email,
    Boolean enabled
) {

}
