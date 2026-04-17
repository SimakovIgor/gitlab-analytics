package io.simakov.analytics.api.dto.request;

public record UpdateTrackedUserRequest(
    String displayName,
    String email,
    Boolean enabled
) {

}
