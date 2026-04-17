package io.simakov.analytics.api.dto.response;

import java.time.Instant;
import java.util.List;

public record TrackedUserResponse(
    Long id,
    String displayName,
    String email,
    boolean enabled,
    Instant createdAt,
    List<AliasResponse> aliases
) {

    public record AliasResponse(
        Long id,
        Long gitlabUserId,
        String username,
        String email,
        String name
    ) {
    }
}
