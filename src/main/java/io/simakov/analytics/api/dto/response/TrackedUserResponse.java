package io.simakov.analytics.api.dto.response;

import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;

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

    public static TrackedUserResponse from(TrackedUser user,
                                           List<TrackedUserAlias> aliases) {
        return new TrackedUserResponse(
            user.getId(), user.getDisplayName(), user.getEmail(), user.isEnabled(),
            user.getCreatedAt(),
            aliases.stream().map(AliasResponse::from).toList());
    }

    public record AliasResponse(
        Long id,
        Long gitlabUserId,
        String username,
        String email,
        String name
    ) {

        public static AliasResponse from(TrackedUserAlias a) {
            return new AliasResponse(a.getId(), a.getGitlabUserId(), a.getUsername(), a.getEmail(), a.getName());
        }
    }
}
