package io.simakov.analytics.workspace;

import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.domain.model.Team;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.repository.TeamRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.web.dto.TeamDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TrackedUserRepository trackedUserRepository;

    public List<TeamDto> listTeams(Long workspaceId) {
        List<Team> teams = teamRepository.findByWorkspaceId(workspaceId);
        if (teams.isEmpty()) {
            return List.of();
        }
        List<Long> teamIds = teams.stream().map(Team::getId).toList();
        List<TrackedUser> allMembers = trackedUserRepository.findByWorkspaceIdAndTeamIdIn(workspaceId, teamIds);
        Map<Long, List<TrackedUser>> byTeam = allMembers.stream()
            .collect(Collectors.groupingBy(TrackedUser::getTeamId));
        return teams.stream()
            .map(t -> TeamDto.of(t, byTeam.getOrDefault(t.getId(), List.of())))
            .toList();
    }

    @Transactional
    public TeamDto createTeam(Long workspaceId, String name, int colorIndex, List<Long> memberIds) {
        Team team = teamRepository.save(Team.builder()
            .workspaceId(workspaceId)
            .name(name)
            .colorIndex(colorIndex)
            .build());
        if (!memberIds.isEmpty()) {
            trackedUserRepository.assignTeamId(workspaceId, memberIds, team.getId());
        }
        List<TrackedUser> members = trackedUserRepository.findByWorkspaceIdAndTeamIdIn(
            workspaceId, List.of(team.getId()));
        return TeamDto.of(team, members);
    }

    @Transactional
    public TeamDto updateTeam(Long workspaceId, Long teamId, String name, int colorIndex, List<Long> memberIds) {
        Team team = teamRepository.findByWorkspaceIdAndId(workspaceId, teamId)
            .orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
        team.setName(name);
        team.setColorIndex(colorIndex);
        teamRepository.save(team);

        // Replace member set: clear current, assign new
        trackedUserRepository.clearTeamId(workspaceId, teamId);
        if (!memberIds.isEmpty()) {
            trackedUserRepository.assignTeamId(workspaceId, memberIds, teamId);
        }

        List<TrackedUser> members = trackedUserRepository.findByWorkspaceIdAndTeamIdIn(
            workspaceId, List.of(teamId));
        return TeamDto.of(team, members);
    }

    @Transactional
    public void deleteTeam(Long workspaceId, Long teamId) {
        if (!teamRepository.findByWorkspaceIdAndId(workspaceId, teamId).isPresent()) {
            throw new ResourceNotFoundException("Team", teamId);
        }
        // ON DELETE SET NULL FK constraint handles nulling out tracked_user.team_id
        teamRepository.deleteByWorkspaceIdAndId(workspaceId, teamId);
    }

    public int countTeams(Long workspaceId) {
        return teamRepository.countByWorkspaceId(workspaceId);
    }
}
