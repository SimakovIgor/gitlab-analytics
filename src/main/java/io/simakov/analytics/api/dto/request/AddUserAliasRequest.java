package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record AddUserAliasRequest(
    @NotNull Long gitlabUserId,
    String username,
    @Email String email,
    String name
) {

}
