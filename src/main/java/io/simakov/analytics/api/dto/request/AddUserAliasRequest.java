package io.simakov.analytics.api.dto.request;

public record AddUserAliasRequest(
    Long gitlabUserId,
    String username,
    String email,
    String name
) {

}
