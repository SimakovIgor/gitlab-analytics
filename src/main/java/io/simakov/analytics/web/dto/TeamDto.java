package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedUser;

import java.util.List;

public record TeamDto(
    Long id,
    String name,
    int colorIndex,
    int memberCount,
    List<MemberInfo> members,
    List<Long> projectIds
) {

    public static TeamDto of(Team team, List<TrackedUser> members, List<Long> projectIds) {
        return new TeamDto(
            team.getId(),
            team.getName(),
            team.getColorIndex(),
            members.size(),
            members.stream()
                .map(u -> new MemberInfo(u.getId(), u.getDisplayName()))
                .toList(),
            projectIds
        );
    }

    public record MemberInfo(Long id, String displayName) {
    }
}
