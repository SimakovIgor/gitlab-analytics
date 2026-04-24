package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedUser;

import java.util.List;

public record TeamDto(
    Long id,
    String name,
    int colorIndex,
    int memberCount,
    List<MemberInfo> members
) {

    public static TeamDto of(Team team, List<TrackedUser> members) {
        return new TeamDto(
            team.getId(),
            team.getName(),
            team.getColorIndex(),
            members.size(),
            members.stream()
                .map(u -> new MemberInfo(u.getId(), u.getDisplayName()))
                .toList()
        );
    }

    public record MemberInfo(Long id, String displayName) {
    }
}
