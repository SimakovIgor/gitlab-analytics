package io.simakov.analytics.api.dto.response;

import java.time.Instant;

public record TrackedProjectResponse(
    Long id,
    Long gitSourceId,
    Long gitlabProjectId,
    String pathWithNamespace,
    String name,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {

}
