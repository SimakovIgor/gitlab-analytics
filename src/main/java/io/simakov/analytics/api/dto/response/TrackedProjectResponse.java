package io.simakov.analytics.api.dto.response;

import io.simakov.analytics.domain.model.TrackedProject;

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

    public static TrackedProjectResponse from(TrackedProject p) {
        return new TrackedProjectResponse(p.getId(), p.getGitSourceId(), p.getGitlabProjectId(),
            p.getPathWithNamespace(), p.getName(), p.isEnabled(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
