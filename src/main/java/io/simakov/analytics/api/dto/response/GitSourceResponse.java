package io.simakov.analytics.api.dto.response;

import io.simakov.analytics.domain.model.GitSource;

import java.time.Instant;

public record GitSourceResponse(
    Long id,
    String name,
    String baseUrl,
    Instant createdAt,
    Instant updatedAt
) {

    public static GitSourceResponse from(GitSource source) {
        return new GitSourceResponse(source.getId(), source.getName(), source.getBaseUrl(),
            source.getCreatedAt(), source.getUpdatedAt());
    }
}
